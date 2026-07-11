package com.alianga.idea.deploy.ui.toolwindow

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.RemoteFileEntry
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.RemoteFileBrowserService
import com.alianga.idea.deploy.service.ServerManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * 远程文件浏览器面板（工具窗口内容）。
 *
 * 以树形结构浏览远程服务器文件系统，支持：
 * - 查看目录结构（懒加载）、双击文件在 IDE 主编辑器中打开（支持语法高亮，Ctrl+S 自动写回远程）
 * - 下载远程文件/目录、拖拽到本项目直接下载、拖拽本地文件到浏览器上传
 * - 新建目录、删除条目、复制远程路径、刷新
 * - 首次打开按当前项目路径映射自动定位远程目录；记忆每台服务器上次打开的目录
 * - 路径输入框记忆每台服务器手动输入并成功进入过的路径（可清空/删除单条）
 *
 * 通过 [RemoteFileBrowserService.BrowserSession] 持有可复用 SFTP 通道，
 * 面板 [dispose] 时释放。切换服务器时重建会话。
 */
class RemoteFileBrowserPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(RemoteFileBrowserPanel::class.java)
        private val FOLDER_ICON = AllIcons.Nodes.Folder
        private val FILE_ICON = AllIcons.FileTypes.Any_type
        private val LINK_ICON = AllIcons.Nodes.Symlink
        private val LOADING_ICON = AllIcons.Actions.Refresh
    }

    private val serverManager = ServerManager.getInstance()
    private val browserService = RemoteFileBrowserService.getInstance()
    private val settings = FileSyncSettings.getInstance()

    private val serverCombo = JComboBox<String>()
    private val pathCombo = JComboBox<String>().apply { isEditable = true }
    private val goButton = JButton(AllIcons.Actions.Forward)
    private val upButton = JButton(AllIcons.Actions.Upload)
    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private val newDirButton = JButton(AllIcons.Actions.NewFolder)
    private val historyButton = JButton(AllIcons.Vcs.History)

    private val statusLabel = JBLabel(DeployXBundle.message("remote.browser.status.ready"))

    private val rootBrowseNode = RemoteFileNode(syntheticDirEntry("/"))
    private val treeModel = DefaultTreeModel(rootBrowseNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = true
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = RemoteFileCellRenderer()
        toggleClickCount = 0
        dragEnabled = true
        transferHandler = BrowserTransferHandler()
    }

    private var session: RemoteFileBrowserService.BrowserSession? = null
    private var currentServer: ServerConfig? = null
    private var currentPath: String = "/"
    @Volatile private var isDisposed = false
    @Volatile private var isLoading = false
    /** 抑制切换服务器/刷新历史时 pathCombo 触发的事件 */
    private var suppressPathAction = false

    init {
        goButton.toolTipText = DeployXBundle.message("remote.browser.button.go")
        upButton.toolTipText = DeployXBundle.message("remote.browser.button.up")
        refreshButton.toolTipText = DeployXBundle.message("remote.browser.refresh")
        newDirButton.toolTipText = DeployXBundle.message("remote.browser.newDir")
        historyButton.toolTipText = DeployXBundle.message("remote.browser.button.history")
        setupUI()
        buildLayout()
        populateServers()
    }

    private fun setupUI() {
        serverCombo.addActionListener {
            val server = selectedServer() ?: return@addActionListener
            if (server.id == currentServer?.id) return@addActionListener
            switchServer(server)
        }

        goButton.addActionListener { loadPath(pathComboText()) }
        pathCombo.addActionListener {
            if (suppressPathAction) return@addActionListener
            loadPath(pathComboText())
        }
        upButton.addActionListener { goUp() }
        refreshButton.addActionListener { refreshCurrent() }
        newDirButton.addActionListener { createNewDirectory(currentPath, rootBrowseNode) }
        historyButton.addActionListener { showPathHistoryPopup(historyButton, 0, historyButton.height) }

        // 路径输入框右键：管理历史
        (pathCombo.editor.editorComponent as? JComponent)?.let { comp ->
            comp.componentPopupMenu = JPopupMenu().apply {
                add(JMenuItem(DeployXBundle.message("remote.browser.button.history")).apply {
                    addActionListener { showPathHistoryPopup(comp, 0, 0) }
                })
            }
        }

        tree.addTreeWillExpandListener(object : javax.swing.event.TreeWillExpandListener {
            override fun treeWillExpand(e: javax.swing.event.TreeExpansionEvent) {
                val node = e.path.lastPathComponent as? RemoteFileNode ?: return
                if (node.isDirectory() && !node.loaded && !node.isLoadingPlaceholder) {
                    loadChildren(node)
                }
            }
            override fun treeWillCollapse(e: javax.swing.event.TreeExpansionEvent) {}
        })

        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? RemoteFileNode
            statusLabel.text = if (node?.entry != null) {
                DeployXBundle.message("remote.browser.status.selected", node.entry!!.path + if (node.entry!!.isDirectory) "/" else "")
            } else {
                DeployXBundle.message("remote.browser.status.ready")
            }
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = nodeAtPoint(e.point) ?: return
                val entry = node.entry ?: return
                if (entry.isDirectory) {
                    if (!node.loaded) loadChildren(node)
                    val path = TreePath(node.path)
                    if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
                } else {
                    openInEditor(entry)
                }
            }
            override fun mousePressed(e: MouseEvent) { maybeShowPopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e) }
        })
    }

    private fun buildLayout() {
        val serverPanel = JPanel(BorderLayout(6, 0)).apply {
            add(JBLabel(DeployXBundle.message("remote.browser.label.server")), BorderLayout.WEST)
            add(serverCombo, BorderLayout.CENTER)
        }
        val pathPanel = JPanel(BorderLayout(6, 0)).apply {
            add(JBLabel(DeployXBundle.message("remote.browser.label.path")), BorderLayout.WEST)
            add(pathCombo, BorderLayout.CENTER)
            val btnPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(goButton); add(Box.createHorizontalStrut(4))
                add(upButton); add(Box.createHorizontalStrut(4))
                add(refreshButton); add(Box.createHorizontalStrut(4))
                add(newDirButton); add(Box.createHorizontalStrut(4))
                add(historyButton)
            }
            add(btnPanel, BorderLayout.EAST)
        }
        val treeScroll = JBScrollPane(tree).apply { preferredSize = Dimension(500, 400) }
        val hintLabel = JBLabel(DeployXBundle.message("remote.browser.hint")).apply { foreground = JBColor.GRAY }

        val content = FormBuilder.createFormBuilder()
            .addComponent(serverPanel)
            .addVerticalGap(6)
            .addComponent(pathPanel)
            .addVerticalGap(6)
            .addComponentFillVertically(treeScroll, 0)
            .addVerticalGap(6)
            .addComponent(hintLabel)
            .addVerticalGap(4)
            .addComponent(statusLabel)
            .panel
        add(content, BorderLayout.CENTER)
    }

    // ===== 服务器与路径管理 =====

    private fun populateServers() {
        serverCombo.removeAllItems()
        val servers = serverManager.getServers()
        if (servers.isEmpty()) {
            statusLabel.text = DeployXBundle.message("remote.browser.empty")
            return
        }
        for (server in servers) serverCombo.addItem("${server.id} - ${server.name}")
        val defaultServer = serverManager.getDefaultServer() ?: servers.first()
        serverCombo.selectedIndex = servers.indexOfFirst { it.id == defaultServer.id }.coerceAtLeast(0)
        switchServer(defaultServer)
    }

    private fun selectedServer(): ServerConfig? {
        val selected = serverCombo.selectedItem?.toString() ?: return null
        return serverManager.getServer(selected.substringBefore(" - "))
    }

    private fun switchServer(server: ServerConfig) {
        currentServer = server
        session?.close()
        session = browserService.openSession(server)
        // 初始路径：上次打开的 > 按项目路径映射自动定位 > 根目录
        val initialPath = settings.getBrowserLastPath(server.id) ?: autoMapRemotePath(server) ?: "/"
        refreshPathCombo()
        suppressPathAction = true
        pathCombo.selectedItem = initialPath
        suppressPathAction = false
        loadPath(initialPath)
    }

    /** 按当前项目路径匹配映射，自动定位远程目录 */
    private fun autoMapRemotePath(server: ServerConfig): String? {
        val projectPath = project.basePath ?: return null
        return MappingManager.getInstance().findMappingsByLocalPath(projectPath)
            .firstOrNull { it.serverId == server.id }?.remoteDir
    }

    private fun loadPath(path: String) {
        currentPath = normalizePath(path)
        suppressPathAction = true
        pathCombo.selectedItem = currentPath
        suppressPathAction = false
        // 记忆上次打开的目录
        currentServer?.let { settings.setBrowserLastPath(it.id, currentPath) }
        reloadRoot(currentPath)
    }

    private fun goUp() {
        if (currentPath == "/" || currentPath.isBlank()) return
        loadPath(currentPath.trimEnd('/').substringBeforeLast('/').ifBlank { "/" })
    }

    private fun refreshCurrent() = reloadRoot(currentPath)

    private fun reloadRoot(path: String) {
        val srv = currentServer ?: return
        val sess = session ?: return
        rootBrowseNode.entry = syntheticDirEntry(path)
        rootBrowseNode.loaded = false
        rootBrowseNode.removeAllChildren()
        treeModel.reload(rootBrowseNode)
        statusLabel.text = DeployXBundle.message("remote.browser.loading")
        loadChildren(rootBrowseNode, sess, srv)
    }

    private fun loadChildren(
        node: RemoteFileNode,
        sess: RemoteFileBrowserService.BrowserSession? = session,
        srv: ServerConfig? = currentServer
    ) {
        if (node.loaded || node.isLoadingPlaceholder) return
        val path = node.entry?.path ?: return
        node.loaded = true
        node.add(RemoteFileNode(null).apply { isLoadingPlaceholder = true })
        treeModel.reload(node)
        isLoading = true

        ProgressManager.getInstance().run(object : Task.Backgroundable(null, DeployXBundle.message("remote.browser.loading"), true) {
            override fun run(indicator: ProgressIndicator) {
                var entries: List<RemoteFileEntry>? = null
                var error: String? = null
                try { indicator.checkCanceled(); entries = sess?.listDirectory(path) }
                catch (e: ProcessCanceledException) { return }
                catch (e: Exception) { LOG.warn("Failed to list: $path", e); error = e.message ?: e.toString() }

                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    isLoading = false
                    node.removeAllChildren()
                    if (error != null) {
                        statusLabel.text = DeployXBundle.message("remote.browser.load.failed", error)
                        node.loaded = false
                        treeModel.reload(node)
                        if (node === rootBrowseNode) {
                            Messages.showErrorDialog(this@RemoteFileBrowserPanel, DeployXBundle.message("remote.browser.load.failed", error), DeployXBundle.message("remote.browser.error.title"))
                        }
                    } else {
                        entries?.forEach { node.add(RemoteFileNode(it)) }
                        treeModel.reload(node)
                        statusLabel.text = DeployXBundle.message("remote.browser.status.loaded", entries?.size ?: 0, path)
                        // 根节点加载成功：记录路径历史 + 自动展开
                        if (node === rootBrowseNode) {
                            srv?.let { settings.addBrowserPathHistory(it.id, path) }
                            refreshPathCombo()
                            tree.expandPath(TreePath(node.path))
                        }
                    }
                }
            }
        })
    }

    // ===== 路径历史 =====

    private fun refreshPathCombo() {
        val serverId = currentServer?.id ?: return
        val history = settings.getBrowserPathHistory(serverId)
        suppressPathAction = true
        pathCombo.removeAllItems()
        for (p in history) pathCombo.addItem(p)
        pathCombo.selectedItem = currentPath
        suppressPathAction = false
    }

    private fun showPathHistoryPopup(invoker: JComponent, x: Int, y: Int) {
        val serverId = currentServer?.id ?: return
        val history = settings.getBrowserPathHistory(serverId)
        val popup = JPopupMenu()
        if (history.isEmpty()) {
            popup.add(JMenuItem(DeployXBundle.message("remote.browser.history.empty")).apply { isEnabled = false })
        } else {
            for (path in history) {
                popup.add(JMenuItem(path).apply { addActionListener { loadPath(path) } })
            }
            popup.addSeparator()
            val deleteMenu = JMenu(DeployXBundle.message("remote.browser.history.deleteEntry"))
            for (path in history) {
                deleteMenu.add(JMenuItem(path).apply {
                    addActionListener {
                        settings.removeBrowserPathHistory(serverId, path)
                        refreshPathCombo()
                        statusLabel.text = DeployXBundle.message("remote.browser.history.removed", path)
                    }
                })
            }
            popup.add(deleteMenu)
            popup.add(JMenuItem(DeployXBundle.message("remote.browser.history.clearAll")).apply {
                addActionListener {
                    settings.clearBrowserPathHistory(serverId)
                    refreshPathCombo()
                    statusLabel.text = DeployXBundle.message("remote.browser.history.cleared")
                }
            })
        }
        popup.show(invoker, x, y)
    }

    // ===== 编辑/下载/删除/新建 =====

    private fun openInEditor(entry: RemoteFileEntry) {
        val server = currentServer ?: return
        browserService.openInEditor(project, server, entry.path, entry.name)
    }

    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val node = nodeAtPoint(e.point) ?: return
        tree.selectionPath = TreePath(node.path)
        val entry = node.entry ?: return
        showContextMenu(node, entry, e.x, e.y)
    }

    private fun nodeAtPoint(point: java.awt.Point): RemoteFileNode? {
        val path = tree.getPathForLocation(point.x, point.y) ?: return null
        return path.lastPathComponent as? RemoteFileNode
    }

    private fun showContextMenu(node: RemoteFileNode, entry: RemoteFileEntry, x: Int, y: Int) {
        val popup = JPopupMenu()
        if (!entry.isDirectory) {
            popup.add(JMenuItem(DeployXBundle.message("remote.browser.menu.open"), AllIcons.Actions.Edit).apply {
                addActionListener { openInEditor(entry) }
            })
        }
        popup.add(JMenuItem(DeployXBundle.message("remote.browser.menu.download"), AllIcons.Actions.Download).apply {
            addActionListener { downloadEntry(entry) }
        })
        popup.addSeparator()
        popup.add(JMenuItem(DeployXBundle.message("remote.browser.menu.newDir"), AllIcons.Actions.NewFolder).apply {
            val targetDir = if (entry.isDirectory) entry.path else entry.path.substringBeforeLast('/').ifBlank { "/" }
            addActionListener { createNewDirectory(targetDir, if (entry.isDirectory) node else node.parent as? RemoteFileNode) }
        })
        popup.add(JMenuItem(DeployXBundle.message("remote.browser.menu.refresh"), AllIcons.Actions.Refresh).apply {
            val target = if (entry.isDirectory) node else node.parent as? RemoteFileNode
            addActionListener {
                target?.let { it.loaded = false; it.removeAllChildren(); treeModel.reload(it); loadChildren(it) }
            }
        })
        popup.add(JMenuItem(DeployXBundle.message("remote.browser.menu.copyPath"), AllIcons.Actions.Copy).apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(entry.path))
                statusLabel.text = DeployXBundle.message("remote.browser.copyPath.success", entry.path)
            }
        })
        popup.addSeparator()
        popup.add(JMenuItem(DeployXBundle.message("remote.browser.menu.delete"), AllIcons.Actions.Cancel).apply {
            addActionListener { deleteEntry(node, entry) }
        })
        popup.show(tree, x, y)
    }

    private fun downloadEntry(entry: RemoteFileEntry) {
        val sess = session ?: return
        val chooser = JFileChooser().apply {
            if (entry.isDirectory) { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = DeployXBundle.message("remote.browser.download.dirTitle") }
            else { fileSelectionMode = JFileChooser.FILES_ONLY; dialogTitle = DeployXBundle.message("remote.browser.download.title"); selectedFile = File(entry.name) }
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val localTarget = chooser.selectedFile.absolutePath
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, DeployXBundle.message("remote.browser.menu.download"), true) {
            override fun run(indicator: ProgressIndicator) {
                var error: String? = null; var fileCount = 0; var totalSize = 0L
                try {
                    indicator.checkCanceled()
                    if (entry.isDirectory) { val (c, s) = sess.downloadDirectory(entry.path, localTarget); fileCount = c; totalSize = s }
                    else { sess.downloadFile(entry.path, localTarget); fileCount = 1; totalSize = entry.size }
                } catch (e: ProcessCanceledException) { return }
                catch (e: Exception) { LOG.warn("Download failed: ${entry.path}", e); error = e.message ?: e.toString() }
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    if (error != null) Messages.showErrorDialog(this@RemoteFileBrowserPanel, DeployXBundle.message("remote.browser.download.failed", error), DeployXBundle.message("remote.browser.error.title"))
                    else { val msg = DeployXBundle.message("remote.browser.download.success", entry.name, fileCount, localTarget, RemoteFileEntry.formatSize(totalSize)); Messages.showInfoMessage(this@RemoteFileBrowserPanel, msg, DeployXBundle.message("remote.browser.success.title")); statusLabel.text = msg }
                }
            }
        })
    }

    private fun createNewDirectory(parentDir: String, parentNode: RemoteFileNode?) {
        val sess = session ?: return
        val dirName = Messages.showInputDialog(this, DeployXBundle.message("remote.browser.newdir.prompt"), DeployXBundle.message("remote.browser.newdir.title"), Messages.getQuestionIcon())?.trim() ?: return
        if (dirName.isBlank()) return
        if (dirName.contains("/") || dirName.contains("\\")) {
            Messages.showErrorDialog(this, DeployXBundle.message("remote.browser.newdir.failed", DeployXBundle.message("remote.browser.newdir.invalidName")), DeployXBundle.message("remote.browser.error.title")); return
        }
        val fullPath = joinPath(parentDir, dirName)
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, DeployXBundle.message("remote.browser.newdir.title"), true) {
            override fun run(indicator: ProgressIndicator) {
                var error: String? = null
                try { indicator.checkCanceled(); sess.createDirectory(fullPath) }
                catch (e: ProcessCanceledException) { return }
                catch (e: Exception) { LOG.warn("mkdir failed: $fullPath", e); error = e.message ?: e.toString() }
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    if (error != null) Messages.showErrorDialog(this@RemoteFileBrowserPanel, DeployXBundle.message("remote.browser.newdir.failed", error), DeployXBundle.message("remote.browser.error.title"))
                    else { parentNode?.let { it.loaded = false; it.removeAllChildren(); treeModel.reload(it); loadChildren(it) }; statusLabel.text = DeployXBundle.message("remote.browser.newdir.success", fullPath) }
                }
            }
        })
    }

    private fun deleteEntry(node: RemoteFileNode, entry: RemoteFileEntry) {
        val sess = session ?: return
        val typeLabel = if (entry.isDirectory) DeployXBundle.message("remote.browser.type.directory") else DeployXBundle.message("remote.browser.type.file")
        if (Messages.showYesNoDialog(this, DeployXBundle.message("remote.browser.delete.confirm", typeLabel, entry.path), DeployXBundle.message("remote.browser.delete.title"), Messages.getWarningIcon()) != Messages.YES) return
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, DeployXBundle.message("remote.browser.menu.delete"), true) {
            override fun run(indicator: ProgressIndicator) {
                var error: String? = null
                try { indicator.checkCanceled(); if (entry.isDirectory) sess.deleteDirectory(entry.path) else sess.deleteFile(entry.path) }
                catch (e: ProcessCanceledException) { return }
                catch (e: Exception) { LOG.warn("delete failed: ${entry.path}", e); error = e.message ?: e.toString() }
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    if (error != null) Messages.showErrorDialog(this@RemoteFileBrowserPanel, DeployXBundle.message("remote.browser.delete.failed", error), DeployXBundle.message("remote.browser.error.title"))
                    else { (node.parent as? RemoteFileNode)?.let { it.remove(node); treeModel.reload(it) }; statusLabel.text = DeployXBundle.message("remote.browser.delete.done", entry.path) }
                }
            }
        })
    }

    // ===== 拖拽上传 =====

    /** 拖拽本地文件到树上传 */
    private fun uploadFiles(files: List<File>, targetDir: String) {
        val sess = session ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(null, DeployXBundle.message("remote.browser.upload.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                var count = 0; var totalSize = 0L; var error: String? = null
                try {
                    for (file in files) {
                        indicator.checkCanceled()
                        val remotePath = joinPath(targetDir, file.name)
                        if (file.isDirectory) { val (c, s) = sess.uploadDirectory(file.absolutePath, remotePath); count += c; totalSize += s }
                        else { sess.uploadFile(file.absolutePath, remotePath); count++; totalSize += file.length() }
                    }
                } catch (e: ProcessCanceledException) { return }
                catch (e: Exception) { LOG.warn("Upload failed to $targetDir", e); error = e.message ?: e.toString() }
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    if (error != null) { Messages.showErrorDialog(this@RemoteFileBrowserPanel, DeployXBundle.message("remote.browser.upload.failed", error), DeployXBundle.message("remote.browser.error.title")) }
                    else {
                        statusLabel.text = DeployXBundle.message("remote.browser.upload.success", count, targetDir, RemoteFileEntry.formatSize(totalSize))
                        // 刷新目标目录
                        findNodeForPath(targetDir)?.let { it.loaded = false; it.removeAllChildren(); treeModel.reload(it); loadChildren(it) }
                    }
                }
            }
        })
    }

    /** 在当前展开的树中查找对应路径的节点 */
    private fun findNodeForPath(path: String): RemoteFileNode? {
        val normalized = normalizePath(path)
        if (rootBrowseNode.entry?.path == normalized) return rootBrowseNode
        return findNodeInChildren(rootBrowseNode, normalized)
    }

    private fun findNodeInChildren(node: RemoteFileNode, path: String): RemoteFileNode? {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? RemoteFileNode ?: continue
            if (child.entry?.path == path) return child
            if (child.entry?.isDirectory == true && path.startsWith(child.entry!!.path + "/")) {
                findNodeInChildren(child, path)?.let { return it }
            }
        }
        return null
    }

    // ===== 拖拽下载支持 =====

    private fun getSelectedEntries(): List<RemoteFileEntry> {
        return tree.getSelectionPaths()?.mapNotNull { (it.lastPathComponent as? RemoteFileNode)?.entry } ?: emptyList()
    }

    private fun getDropTargetDir(dropLocation: TransferHandler.DropLocation?): String {
        if (dropLocation is javax.swing.JTree.DropLocation) {
            val node = dropLocation.path?.lastPathComponent as? RemoteFileNode
            if (node != null) {
                node.entry?.let { if (it.isDirectory) return it.path }
                (node.parent as? RemoteFileNode)?.entry?.let { return it.path }
            }
        }
        return currentPath
    }

    // ===== 工具方法 =====

    private fun pathComboText(): String = (pathCombo.editor.editorComponent as? javax.swing.text.JTextComponent)?.text?.trim()?.ifBlank { "/" } ?: "/"

    private fun normalizePath(path: String): String { val p = path.trim().replace("\\", "/"); return if (p.isBlank()) "/" else p }
    private fun joinPath(base: String, name: String): String { val b = base.trimEnd('/'); return if (b.isBlank() || b == "/") "/$name" else "$b/$name" }
    private fun syntheticDirEntry(path: String): RemoteFileEntry = RemoteFileEntry(name = path, path = normalizePath(path), isDirectory = true)

    /** 释放会话资源（工具窗口隐藏时调用） */
    fun dispose() {
        isDisposed = true
        session?.close()
        session = null
    }

    // ===== TransferHandler：拖拽上传 + 拖拽下载 =====

    private inner class BrowserTransferHandler : TransferHandler() {
        /** 导入（本地文件拖入树）：上传 */
        override fun canImport(support: TransferSupport): Boolean =
            support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

        override fun importData(support: TransferSupport): Boolean {
            if (!support.isDrop || !canImport(support)) return false
            val sess = session ?: return false
            return try {
                @Suppress("UNCHECKED_CAST")
                val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                val targetDir = getDropTargetDir(support.dropLocation)
                uploadFiles(files, targetDir)
                true
            } catch (e: Exception) {
                LOG.warn("Drop import failed", e)
                false
            }
        }

        /** 导出（树节点拖出到项目视图）：下载到临时目录后以文件列表提供 */
        override fun getSourceActions(c: JComponent?): Int = COPY

        override fun createTransferable(c: JComponent?): Transferable? {
            val entries = getSelectedEntries()
            if (entries.isEmpty()) return null
            val sess = session ?: return null
            return RemoteEntriesTransferable(entries, sess)
        }
    }

    /**
     * 远程条目传输器：在 [getTransferData] 时将远程文件/目录下载到临时目录，
     * 以 javaFileListFlavor 形式提供给放置目标（如项目视图）。
     */
    private class RemoteEntriesTransferable(
        private val entries: List<RemoteFileEntry>,
        private val session: RemoteFileBrowserService.BrowserSession
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
            val tempDir = Files.createTempDirectory("deployx-dnd-").toFile()
            val result = mutableListOf<File>()
            for (entry in entries) {
                val target = File(tempDir, entry.name)
                if (entry.isDirectory) session.downloadDirectory(entry.path, target.absolutePath)
                else session.downloadFile(entry.path, target.absolutePath)
                result.add(target)
            }
            return result
        }
    }

    // ===== 树节点与渲染器 =====

    private class RemoteFileNode(@JvmField var entry: RemoteFileEntry?) : DefaultMutableTreeNode() {
        var loaded = false
        var isLoadingPlaceholder = false
        init { allowsChildren = entry?.isDirectory != false }
        fun isDirectory(): Boolean = entry?.isDirectory != false
    }

    private class RemoteFileCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? RemoteFileNode ?: return
            if (node.isLoadingPlaceholder) {
                icon = LOADING_ICON
                append(DeployXBundle.message("remote.browser.loading"), SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
                return
            }
            val entry = node.entry ?: return
            icon = when { entry.isLink -> LINK_ICON; entry.isDirectory -> FOLDER_ICON; else -> FILE_ICON }
            if (entry.isDirectory) append(entry.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.BLUE))
            else append(entry.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.foreground()))
            if (!entry.isDirectory && entry.size > 0L) append("  (${entry.displaySize})", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.GRAY), false)
        }
    }
}
