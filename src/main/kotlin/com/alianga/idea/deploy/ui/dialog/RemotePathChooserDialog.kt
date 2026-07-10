package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.ssh.SshConnection
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.*

/**
 * 远程路径选择对话框
 * 使用后台线程加载目录，避免 UI 阻塞
 */
class RemotePathChooserDialog(
    private val server: ServerConfig,
    private val initialPath: String = "/"
) : DialogWrapper(null) {

    companion object {
        private val LOG = Logger.getInstance(RemotePathChooserDialog::class.java)
    }

    private val pathField = JBTextField(initialPath)
    private val directoryList = JBList<String>()
    private val listModel = DefaultListModel<String>()
    private val loadButton = JButton(DeployXBundle.message("dialog.remote.path.button.loadDirectory"))
    private val upButton = JButton(DeployXBundle.message("dialog.remote.path.button.parentDirectory"))
    private val newButton = JButton(DeployXBundle.message("dialog.remote.path.button.newDirectory"))

    private var currentPath: String = initialPath
    private var isLoading = false
        set(value) {
            field = value
            updateUIState()
        }

    /**
     * 取消标志：当用户关闭对话框时设为 true，后台任务检查后提前退出，
     * 避免对话框关闭后访问已失效的 UI 组件
     */
    @Volatile
    private var isCancelled = false

    init {
        title = DeployXBundle.message("dialog.remote.path.title", server.name, server.displayAddress)
        directoryList.model = listModel
        setupUI()
        init()
        // 延迟加载，确保对话框 UI 完全初始化
        SwingUtilities.invokeLater {
            loadDirectory(currentPath)
        }
    }

    private fun setupUI() {
        // 设置列表渲染器
        directoryList.cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(
                list: JList<out String>,
                value: String?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                icon = if (value.endsWith("/")) {
                    AllIcons.Nodes.Folder
                } else {
                    AllIcons.FileTypes.Any_type
                }
                if (value.endsWith("/")) {
                    append(value.removeSuffix("/"), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.BLUE))
                } else {
                    append(value, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
                }
            }
        }

        // 双击选择目录
        directoryList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = directoryList.selectedValue
                    if (selected != null && selected.endsWith("/")) {
                        val itemName = selected.removeSuffix("/")
                        val newPath = if (currentPath == "/") {
                            "/$itemName"
                        } else {
                            "$currentPath/$itemName"
                        }
                        pathField.text = newPath
                        loadDirectory(newPath)
                    }
                }
            }
        })

        // 回车选择
        directoryList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    val selected = directoryList.selectedValue
                    if (selected != null && selected.endsWith("/")) {
                        val itemName = selected.removeSuffix("/")
                        val newPath = if (currentPath == "/") {
                            "/$itemName"
                        } else {
                            "$currentPath/$itemName"
                        }
                        pathField.text = newPath
                        loadDirectory(newPath)
                    }
                }
            }
        })

        // 按钮事件
        loadButton.addActionListener {
            loadDirectory(pathField.text.trim())
        }

        upButton.addActionListener {
            goUp()
        }

        newButton.addActionListener {
            createNewDirectory()
        }

        // 路径回车直接加载
        pathField.addActionListener {
            loadDirectory(pathField.text.trim())
        }
    }

    private fun updateUIState() {
        loadButton.isEnabled = !isLoading
        upButton.isEnabled = !isLoading
        newButton.isEnabled = !isLoading
        directoryList.isEnabled = !isLoading
        pathField.isEnabled = !isLoading
        loadButton.text = if (isLoading) {
            DeployXBundle.message("dialog.remote.path.loading")
        } else {
            DeployXBundle.message("dialog.remote.path.button.loadDirectory")
        }
    }

    private fun goUp() {
        if (currentPath == "/" || currentPath.isBlank()) {
            return
        }
        val parentPath = currentPath.substringBeforeLast('/')
        val newPath = if (parentPath.isBlank()) "/" else parentPath
        pathField.text = newPath
        loadDirectory(newPath)
    }

    private fun createNewDirectory() {
        if (isLoading) return

        val dirName = Messages.showInputDialog(
            this@RemotePathChooserDialog.contentPanel,
            DeployXBundle.message("dialog.remote.path.newDirectory.prompt"),
            DeployXBundle.message("dialog.remote.path.newDirectory.title"),
            Messages.getQuestionIcon()
        )?.trim() ?: return

        if (dirName.isBlank()) {
            return
        }

        // 简单验证目录名，不允许包含路径分隔符
        if (dirName.contains("/") || dirName.contains("\\")) {
            Messages.showErrorDialog(
                this@RemotePathChooserDialog.contentPanel,
                DeployXBundle.message("dialog.remote.path.error.createFailed", "目录名称不能包含路径分隔符"),
                DeployXBundle.message("dialog.remote.path.error.title")
            )
            return
        }

        isLoading = true
        updateUIState()
        isCancelled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                null,
                DeployXBundle.message("dialog.remote.path.newDirectory.title"),
                /* canBeCancelled = */ true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    var error: String? = null
                    val connection = SshConnection(server)
                    val fullPath = if (currentPath.endsWith("/")) {
                        "$currentPath$dirName"
                    } else {
                        "$currentPath/$dirName"
                    }

                    try {
                        indicator.checkCanceled()
                        indicator.text = DeployXBundle.message("dialog.remote.path.newDirectory.title")
                        LOG.info("Creating directory: $fullPath on server ${server.displayAddress}")

                        if (!connection.connect()) {
                            error = DeployXBundle.message("dialog.remote.path.error.connectFailed")
                            LOG.warn("Failed to connect to ${server.displayAddress}")
                        } else {
                            indicator.checkCanceled()
                            val mkdirResult = connection.executeCommand("mkdir -p \"$fullPath\"")
                            if (!mkdirResult.success) {
                                error = DeployXBundle.message("dialog.remote.path.error.createFailed", mkdirResult.error)
                                LOG.warn("Failed to create directory: ${mkdirResult.error}")
                            } else {
                                LOG.info("Directory created successfully: $fullPath")
                            }
                        }
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        LOG.info("Directory creation was cancelled")
                    } catch (e: Exception) {
                        LOG.error("Failed to create directory", e)
                        error = DeployXBundle.message("dialog.remote.path.error.createFailed", e.message ?: "")
                    } finally {
                        connection.disconnect()
                    }

                    // 在 UI 线程更新结果
                    ApplicationManager.getApplication().invokeLater {
                        if (!isShowing || isCancelled) {
                            return@invokeLater
                        }
                        isLoading = false
                        updateUIState()
                        if (error != null) {
                            Messages.showErrorDialog(
                                this@RemotePathChooserDialog.contentPanel,
                                error,
                                DeployXBundle.message("dialog.remote.path.error.title")
                            )
                        } else {
                            // 创建成功，重新加载当前目录
                            loadDirectory(currentPath)
                            // 选中新创建的目录
                            val index = listModel.indexOf("$dirName/")
                            if (index != -1) {
                                directoryList.selectedIndex = index
                                directoryList.ensureIndexIsVisible(index)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun loadDirectory(path: String) {
        if (isLoading) return

        val normalizedPath = if (path.isBlank()) "/" else path.trim().replace("\\", "/")
        currentPath = normalizedPath
        pathField.text = normalizedPath
        isLoading = true
        updateUIState()
        isCancelled = false

        LOG.info("Loading remote directory: $normalizedPath for server ${server.displayAddress}")

        // 使用 ProgressManager 后台任务，可取消且不会在对话框关闭后泄漏
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                null,
                DeployXBundle.message("dialog.remote.path.loading"),
                /* canBeCancelled = */ true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    var result: List<String>? = null
                    var error: String? = null
                    val connection = SshConnection(server)

                    try {
                        indicator.checkCanceled()
                        indicator.text = DeployXBundle.message("dialog.remote.path.loading")
                        LOG.info("Connecting to ${server.displayAddress}...")
                        if (!connection.connect()) {
                            error = DeployXBundle.message("dialog.remote.path.error.connectFailed")
                            LOG.warn("Failed to connect to ${server.displayAddress}")
                        } else {
                            indicator.checkCanceled()
                            LOG.info("Connected successfully, executing ls command...")
                            val lsResult = connection.executeCommand("cd \"$normalizedPath\" && ls -1F")
                            LOG.info("ls command result - success: ${lsResult.success}, exitCode: ${lsResult.exitCode}")
                            LOG.info("ls output: ${lsResult.output}")
                            if (lsResult.error.isNotBlank()) {
                                LOG.warn("ls error: ${lsResult.error}")
                            }

                            if (!lsResult.success) {
                                error = DeployXBundle.message("dialog.remote.path.error.cannotReadDir", lsResult.error)
                            } else {
                                result = lsResult.output.split("\n")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() && it != "." && it != ".." }
                                    .sortedWith(compareBy({ !it.endsWith("/") }, { it }))

                                LOG.info("Found ${result?.size ?: 0} items in directory")
                            }
                        }
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        LOG.info("Remote directory loading was cancelled")
                    } catch (e: Exception) {
                        LOG.error("Failed to load remote directory", e)
                        error = DeployXBundle.message("dialog.remote.path.error.loadFailed", e.message ?: "")
                    } finally {
                        connection.disconnect()
                    }

                    // 在 UI 线程更新结果，检查对话框是否已关闭
                    ApplicationManager.getApplication().invokeLater {
                        // 对话框已关闭或任务已取消，跳过 UI 更新
                        if (!isShowing || isCancelled) {
                            return@invokeLater
                        }
                        isLoading = false
                        updateUIState()
                        listModel.clear()
                        if (error != null) {
                            JOptionPane.showMessageDialog(
                                this@RemotePathChooserDialog.contentPanel,
                                error,
                                DeployXBundle.message("dialog.remote.path.error.title"),
                                JOptionPane.ERROR_MESSAGE
                            )
                        } else {
                            result?.forEach { listModel.addElement(it) }
                        }
                    }
                }
            }
        )
    }

    override fun dispose() {
        // 通知后台任务停止
        isCancelled = true
        super.dispose()
    }

    override fun createCenterPanel(): JComponent {
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(loadButton)
            add(Box.createHorizontalStrut(5))
            add(upButton)
            add(Box.createHorizontalStrut(5))
            add(newButton)
        }

        val scrollPane = JBScrollPane(directoryList).apply {
            preferredSize = Dimension(500, 400)
        }

        val hintLabel = JLabel(DeployXBundle.message("dialog.remote.path.hint")).apply {
            foreground = JBColor.GRAY
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(DeployXBundle.message("dialog.remote.path.label.remotePath"), pathField)
            .addComponent(buttonPanel)
            .addVerticalGap(8)
            .addComponent(scrollPane)
            .addVerticalGap(8)
            .addComponent(hintLabel)
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        if (pathField.text.isBlank()) {
            return ValidationInfo(DeployXBundle.message("dialog.remote.path.validation.pathRequired"), pathField)
        }
        return null
    }

    fun getSelectedPath(): String {
        return pathField.text.trim().let {
            if (it.endsWith("/")) it else "$it/"
        }
    }
}
