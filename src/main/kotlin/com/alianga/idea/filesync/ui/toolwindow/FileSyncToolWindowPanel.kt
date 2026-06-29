package com.alianga.idea.filesync.ui.toolwindow

import com.alianga.idea.filesync.model.DeployItem
import com.alianga.idea.filesync.model.DeployRequest
import com.alianga.idea.filesync.model.HistoryRecord
import com.alianga.idea.filesync.model.MappingConfig
import com.alianga.idea.filesync.model.UploadItem
import com.alianga.idea.filesync.model.UpdateReport
import com.alianga.idea.filesync.model.UpdateReportGroup
import com.alianga.idea.filesync.service.DeployService
import com.alianga.idea.filesync.service.HistoryManager
import com.alianga.idea.filesync.service.MappingManager
import com.alianga.idea.filesync.service.ServerManager
import com.alianga.idea.filesync.service.SyncService
import com.alianga.idea.filesync.service.UpdateReportFormatter
import com.alianga.idea.filesync.ui.settings.MappingEditDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * 文件同步工具窗口主面板
 * 通过 companion object 暴露实例引用，供右键菜单 Action 调用
 */
class FileSyncToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    companion object {
        /** 当前活跃的面板实例，供 Action 调用 */
        @Volatile
        var activePanel: FileSyncToolWindowPanel? = null
            private set
    }

    private val serverManager = ServerManager.getInstance()
    private val deployService = DeployService.getInstance()
    private val historyManager = HistoryManager.getInstance()

    // 操作 tab 组件
    private val serverCombo = JComboBox<String>()
    private val localPathField = JBTextField()
    private val remotePathField = JBTextField()
    private val backupCheck = JBCheckBox("部署前备份")
    private val backupDirField = JBTextField()
    private val unzipCheck = JBCheckBox("上传后解压")
    private val unzipDestField = JBTextField()
    private val preCommandField = JBTextField()
    private val postCommandField = JBTextField()

    // 进度
    private val progressBar = JProgressBar(0, 100)
    private val progressLabel = JBLabel("就绪")

    // 日志 tab
    private val logArea = JBTextArea()
    private val logTabbedPane = JBTabbedPane()
    private val serverLogAreas = linkedMapOf<String, JBTextArea>()

    // 历史 tab
    private val historyListModel = DefaultListModel<String>()
    private val historyList = JBList(historyListModel)
    private var historyRecords = listOf<HistoryRecord>()

    // Tab 面板
    private val tabbedPane = JBTabbedPane()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var lastUpdateReport: UpdateReport? = null
    private var lastUpdateReportText: String = ""

    init {
        activePanel = this
        setupUI()
        setupActions()
        refreshServerCombo()
        refreshHistory()
    }

    private fun setupUI() {
        val actionGroup = DefaultActionGroup().apply {
            add(createAction("Settings", AllIcons.General.Settings) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "File Sync Tool")
            })
            add(createAction("Refresh", AllIcons.Actions.Refresh) {
                refreshServerCombo()
                refreshHistory()
            })
            add(createAction("Copy Report", AllIcons.Actions.Copy) {
                copyLastReport()
            })
            add(createAction("Export Report", AllIcons.Actions.MenuSaveall) {
                exportLastReport()
            })
            add(createAction("Clear Log", AllIcons.Actions.GC) {
                logArea.text = ""
                serverLogAreas.values.forEach { it.text = "" }
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("FileSyncToolbar", actionGroup, true)
        toolbar.targetComponent = this

        // ===== 操作面板 =====
        val serverPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("目标服务器:", serverCombo)
            .panel

        val filePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("本地文件:", localPathField)
            .addLabeledComponent("远程路径:", remotePathField)
            .panel

        val deployPanel = FormBuilder.createFormBuilder()
            .addComponent(backupCheck)
            .addLabeledComponent("备份目录:", backupDirField)
            .addComponent(unzipCheck)
            .addLabeledComponent("解压目录:", unzipDestField)
            .addVerticalGap(8)
            .addLabeledComponent("上传前命令:", preCommandField)
            .addLabeledComponent("上传后命令:", postCommandField)
            .panel

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(createActionButton("预览", AllIcons.Actions.Preview) { previewSync() })
            add(Box.createHorizontalStrut(8))
            add(createActionButton("开始部署", AllIcons.Actions.Execute) { startDeploy() })
            add(Box.createHorizontalStrut(8))
            add(createActionButton("快速推送", AllIcons.Actions.Upload) { quickPush() })
            add(Box.createHorizontalStrut(16))
            add(createActionButton("保存为映射", AllIcons.Actions.AddToDictionary) { saveAsMapping() })
        }

        val progressPanel = JPanel(BorderLayout(8, 0)).apply {
            add(progressBar, BorderLayout.CENTER)
            add(progressLabel, BorderLayout.EAST)
        }

        val operationPanel = JPanel()
        operationPanel.layout = BoxLayout(operationPanel, BoxLayout.Y_AXIS)
        operationPanel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        operationPanel.add(serverPanel)
        operationPanel.add(Box.createVerticalStrut(8))
        operationPanel.add(filePanel)
        operationPanel.add(Box.createVerticalStrut(8))
        operationPanel.add(deployPanel)
        operationPanel.add(Box.createVerticalStrut(8))
        operationPanel.add(buttonPanel)
        operationPanel.add(Box.createVerticalStrut(8))
        operationPanel.add(progressPanel)

        // ===== 日志面板 =====
        configureLogArea(logArea)
        logTabbedPane.addTab("全部", AllIcons.Nodes.LogFolder, JBScrollPane(logArea))

        // ===== 历史面板 =====
        historyList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                showHistoryDetail()
            }
        }

        val historyButtonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("刷新", AllIcons.Actions.Refresh).apply {
                addActionListener { refreshHistory() }
            })
            add(Box.createHorizontalStrut(4))
            add(JButton("重新部署", AllIcons.Actions.Execute).apply {
                addActionListener { redeployFromHistory() }
            })
            add(Box.createHorizontalStrut(4))
            add(JButton("填入配置", AllIcons.Actions.Edit).apply {
                addActionListener { fillFromHistory() }
            })
            add(Box.createHorizontalStrut(8))
            add(JButton("清空", AllIcons.Actions.GC).apply {
                addActionListener { clearHistory() }
            })
        }
        val historyPanel = JPanel(BorderLayout()).apply {
            add(historyButtonPanel, BorderLayout.NORTH)
            add(JBScrollPane(historyList), BorderLayout.CENTER)
        }

        // ===== Tab 面板 =====
        tabbedPane.addTab("操作", AllIcons.Actions.Execute, JBScrollPane(operationPanel))
        tabbedPane.addTab("日志", AllIcons.Nodes.LogFolder, logTabbedPane)
        tabbedPane.addTab("历史", AllIcons.Vcs.History, historyPanel)

        setContent(tabbedPane)
        setToolbar(toolbar.component)
    }

    private fun setupActions() {
        backupCheck.addChangeListener { backupDirField.isEnabled = backupCheck.isSelected }
        unzipCheck.addChangeListener { unzipDestField.isEnabled = unzipCheck.isSelected }
        backupDirField.isEnabled = false
        unzipDestField.isEnabled = false
    }

    private fun refreshServerCombo() {
        serverCombo.removeAllItems()
        val servers = serverManager.getServers()
        for (server in servers) {
            serverCombo.addItem("${server.id} - ${server.name}")
        }
        val defaultServer = serverManager.getDefaultServer()
        if (defaultServer != null) {
            val index = servers.indexOfFirst { it.id == defaultServer.id }
            if (index >= 0) serverCombo.selectedIndex = index
        }
    }

    private fun refreshHistory() {
        historyRecords = historyManager.getRecords()
        historyListModel.clear()
        for (record in historyRecords) {
            historyListModel.addElement(record.summary)
        }
    }

    private fun showHistoryDetail() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) return
        val record = historyRecords[idx]
        appendLog("选中历史: ${record.summary}")
        appendLog("  服务器: ${record.serverId}  备份: ${record.backupDir.ifBlank { "无" }}")
        if (record.preCommand.isNotBlank()) appendLog("  上传前命令: ${record.preCommand}")
        if (record.postCommand.isNotBlank()) appendLog("  上传后命令: ${record.postCommand}")
    }

    /** 从历史记录重新部署 */
    private fun redeployFromHistory() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) {
            Messages.showWarningDialog("请先在历史列表中选择一条记录", "重新部署")
            return
        }
        val record = historyRecords[idx]
        val file = java.io.File(record.sourcePath)
        if (!file.exists()) {
            Messages.showWarningDialog("本地文件不存在: ${record.sourcePath}", "重新部署")
            return
        }

        appendLog("========== 重新部署 ==========")
        progressBar.value = 0
        progressLabel.text = "部署中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Redeploying...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = deployService.redeploy(
                    record,
                    logCallback = { line -> appendLog(record.serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    progressBar.value = if (result.success) 100 else progressBar.value
                    progressLabel.text = if (result.success) "部署完成" else "部署失败"
                    refreshHistory()
                }
            }
        })
    }

    /** 将历史记录的配置填入操作面板 */
    private fun fillFromHistory() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) {
            Messages.showWarningDialog("请先在历史列表中选择一条记录", "填入配置")
            return
        }
        val record = historyRecords[idx]
        localPathField.text = record.sourcePath
        remotePathField.text = record.targetPath
        preCommandField.text = record.preCommand
        postCommandField.text = record.postCommand

        if (record.backupDir.isNotBlank()) {
            backupCheck.isSelected = true
            backupDirField.text = record.backupDir
        }
        if (record.unzipDest.isNotBlank()) {
            unzipCheck.isSelected = true
            unzipDestField.text = record.unzipDest
        }

        // 选择对应服务器
        val servers = serverManager.getServers()
        val idx2 = servers.indexOfFirst { it.id == record.serverId }
        if (idx2 >= 0) serverCombo.selectedIndex = idx2

        tabbedPane.selectedIndex = 0 // 切换到操作 tab
        appendLog("已从历史记录填入配置")
    }

    private fun clearHistory() {
        historyManager.clearHistory()
        refreshHistory()
    }

    private fun getSelectedServerId(): String? {
        val selected = serverCombo.selectedItem?.toString() ?: return null
        return selected.substringBefore(" - ")
    }

    /**
     * 公开方法：供 Sync / Quick Push 调用 upload-only 批量上传。
     */
    fun executeUploadBatch(items: List<UploadItem>) {
        if (items.isEmpty()) {
            appendLog("[WARN] 没有可上传的文件")
            return
        }
        appendLog("========== 批量上传，共 ${items.size} 个 ==========")
        progressBar.value = 0
        progressLabel.text = "批量上传中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Batch Uploading...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Batch uploading ${items.size} item(s)..."
                val results = deployService.uploadBatch(
                    items,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    val successCount = results.count { it.success }
                    updateLastReport("UPLOAD", results.mapNotNull { it.reportGroup })
                    progressBar.value = 100
                    progressLabel.text = "批量上传完成：$successCount/${results.size} 组成功"
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用批量部署
     */
    fun executeDeployBatch(items: List<DeployItem>) {
        if (items.isEmpty()) {
            appendLog("[WARN] 没有可执行的部署项")
            return
        }
        appendLog("========== 批量部署，共 ${items.size} 个 ==========")
        progressBar.value = 0
        progressLabel.text = "批量部署中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Batch Deploying...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Batch deploying ${items.size} item(s)..."
                val results = deployService.deployBatch(
                    items,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    val successCount = results.count { it.success }
                    updateLastReport("DEPLOY", results.mapNotNull { it.reportGroup })
                    progressBar.value = 100
                    progressLabel.text = "批量部署完成：$successCount/${results.size} 组成功"
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用 files-from 批量预览。
     */
    fun executePreviewBatch(items: List<UploadItem>) {
        if (items.isEmpty()) {
            appendLog("[WARN] 没有可预览的同步项")
            return
        }
        appendLog("========== 批量预览，共 ${items.size} 个 ==========")
        progressBar.value = 0
        progressLabel.text = "批量预览中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Batch Previewing...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Batch previewing ${items.size} item(s)..."
                val results = deployService.uploadBatch(
                    items,
                    dryRun = true,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    val successCount = results.count { it.success }
                    progressBar.value = 100
                    progressLabel.text = "批量预览完成：$successCount/${results.size} 组成功"
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用部署
     */
    fun executeDeploy(request: DeployRequest) {
        appendLog("========== 开始部署 ==========")
        appendLog("本地: ${request.localPath}")
        appendLog("远程: ${request.serverId}:${request.remotePath}")
        progressBar.value = 0
        progressLabel.text = "部署中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deploying...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = deployService.deploy(
                    request,
                    logCallback = { line -> appendLog(request.serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    progressBar.value = if (result.success) 100 else progressBar.value
                    updateLastReport("DEPLOY", listOfNotNull(result.reportGroup))
                    progressLabel.text = if (result.success) "部署完成" else "部署失败: ${result.error ?: ""}"
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用快速推送
     */
    fun executePush(localPath: String, serverId: String?) {
        appendLog("========== 快速推送 ==========")
        appendLog("本地: $localPath")
        progressBar.value = 0
        progressLabel.text = "推送中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Quick Push...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = deployService.push(
                    localPath,
                    serverId,
                    logCallback = { line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    progressBar.value = if (result.success) 100 else progressBar.value
                    updateLastReport("QUICK_PUSH", listOfNotNull(result.reportGroup))
                    progressLabel.text = if (result.success) "推送完成" else "推送失败: ${result.error ?: ""}"
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用预览
     */
    fun executePreview(localPath: String, remotePath: String, serverId: String) {
        appendLog("========== 预览同步 ==========")
        progressBar.value = 0
        progressLabel.text = "预览中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Previewing Sync...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = SyncService.getInstance().previewSync(localPath, remotePath, serverId) { line ->
                    appendLog(serverId, line)
                }
                SwingUtilities.invokeLater {
                    progressLabel.text = if (result.success) "预览完成" else "预览失败"
                    if (!result.success) appendLog("[ERROR] ${result.error}")
                }
            }
        })
    }

    private fun updateLastReport(operationType: String, groups: List<UpdateReportGroup>) {
        if (groups.isEmpty()) return
        val report = UpdateReport(operationType = operationType, groups = groups)
        lastUpdateReport = report
        lastUpdateReportText = UpdateReportFormatter.format(report)
        appendLog("[REPORT] 已生成更新报告，可复制或导出")
    }

    private fun copyLastReport() {
        if (lastUpdateReportText.isBlank()) {
            Messages.showWarningDialog("当前还没有可复制的更新报告", "Copy Report")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(lastUpdateReportText))
        Messages.showInfoMessage("更新报告已复制到剪贴板", "Copy Report")
    }

    private fun exportLastReport() {
        if (lastUpdateReportText.isBlank()) {
            Messages.showWarningDialog("当前还没有可导出的更新报告", "Export Report")
            return
        }
        val chooser = JFileChooser().apply {
            selectedFile = java.io.File("file-sync-report-${System.currentTimeMillis()}.md")
        }
        val result = chooser.showSaveDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.writeText(lastUpdateReportText)
            Messages.showInfoMessage("更新报告已导出: ${chooser.selectedFile.absolutePath}", "Export Report")
        }
    }

    private fun configureLogArea(area: JBTextArea) {
        area.isEditable = false
        area.font = Font("Monospaced", Font.PLAIN, 12)
        area.lineWrap = true
        area.wrapStyleWord = true
    }

    private fun getOrCreateServerLogArea(serverId: String): JBTextArea {
        return serverLogAreas.getOrPut(serverId) {
            JBTextArea().also { area ->
                configureLogArea(area)
                val server = serverManager.getServer(serverId)
                val title = if (server != null && server.name != server.id) "${server.id} - ${server.name}" else serverId
                logTabbedPane.addTab(title, AllIcons.Nodes.LogFolder, JBScrollPane(area))
            }
        }
    }

    fun appendLog(message: String) {
        appendLog(null, message)
    }

    fun appendLog(serverId: String?, message: String) {
        val time = LocalTime.now().format(timeFormatter)
        val line = "[$time] $message\n"
        val block = {
            logArea.append(line)
            logArea.caretPosition = logArea.document.length
            if (!serverId.isNullOrBlank()) {
                val serverArea = getOrCreateServerLogArea(serverId)
                serverArea.append(line)
                serverArea.caretPosition = serverArea.document.length
            }
            tabbedPane.selectedIndex = 1
        }
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private fun previewSync() {
        val localPath = localPathField.text.trim()
        val remotePath = remotePathField.text.trim()
        val serverId = getSelectedServerId()

        if (localPath.isEmpty() || remotePath.isEmpty() || serverId == null) {
            Messages.showWarningDialog("请填写完整的同步信息", "Preview Sync")
            return
        }

        appendLog("========== 预览同步 ==========")
        progressBar.value = 0
        progressLabel.text = "预览中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Previewing Sync...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "正在预览同步..."
                val result = SyncService.getInstance().previewSync(localPath, remotePath, serverId) { line ->
                    appendLog(serverId, line)
                }
                SwingUtilities.invokeLater {
                    progressLabel.text = if (result.success) "预览完成" else "预览失败"
                    if (!result.success) appendLog("[ERROR] ${result.error}")
                }
            }
        })
    }

    private fun startDeploy() {
        val localPath = localPathField.text.trim()
        val remotePath = remotePathField.text.trim()
        val serverId = getSelectedServerId()

        if (localPath.isEmpty() || remotePath.isEmpty() || serverId == null) {
            Messages.showWarningDialog("请填写完整的部署信息", "Deploy")
            return
        }

        val request = DeployRequest(
            localPath = localPath,
            serverId = serverId,
            remotePath = remotePath,
            backupDir = if (backupCheck.isSelected) backupDirField.text.trim() else null,
            unzipDest = if (unzipCheck.isSelected) unzipDestField.text.trim() else null,
            preCommand = preCommandField.text.trim().ifBlank { null },
            postCommand = postCommandField.text.trim().ifBlank { null }
        )

        appendLog("========== 开始部署 ==========")
        progressBar.value = 0
        progressLabel.text = "部署中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Deploying...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = deployService.deploy(
                    request,
                    logCallback = { line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    progressBar.value = if (result.success) 100 else progressBar.value
                    updateLastReport("MANUAL_DEPLOY", listOfNotNull(result.reportGroup))
                    progressLabel.text = if (result.success) "部署完成" else "部署失败"
                    refreshHistory()
                }
            }
        })
    }

    private fun quickPush() {
        val localPath = localPathField.text.trim()
        val serverId = getSelectedServerId()

        if (localPath.isEmpty()) {
            Messages.showWarningDialog("请填写本地文件路径", "Quick Push")
            return
        }

        appendLog("========== 快速推送 ==========")
        progressBar.value = 0
        progressLabel.text = "推送中..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Quick Push...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = deployService.push(
                    localPath,
                    serverId,
                    logCallback = { line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    }
                )
                SwingUtilities.invokeLater {
                    progressBar.value = if (result.success) 100 else progressBar.value
                    updateLastReport("QUICK_PUSH", listOfNotNull(result.reportGroup))
                    progressLabel.text = if (result.success) "推送完成" else "推送失败"
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 将当前操作面板的配置保存为目录映射
     */
    private fun saveAsMapping() {
        val serverId = getSelectedServerId()
        if (serverId == null) {
            Messages.showWarningDialog("请先选择目标服务器", "保存为映射")
            return
        }
        val localPath = localPathField.text.trim()
        val remotePath = remotePathField.text.trim()
        if (localPath.isBlank() || remotePath.isBlank()) {
            Messages.showWarningDialog("请填写本地文件和远程路径", "保存为映射")
            return
        }

        // 生成默认映射名称
        val localName = java.io.File(localPath).nameWithoutExtension.ifBlank { "mapping" }

        val prefill = MappingConfig(
            name = localName,
            localDir = localPath,
            serverId = serverId,
            remoteDir = remotePath,
            backupEnabled = backupCheck.isSelected,
            backupDir = if (backupCheck.isSelected) backupDirField.text.trim() else "",
            unzipEnabled = unzipCheck.isSelected,
            unzipDest = if (unzipCheck.isSelected) unzipDestField.text.trim() else "",
            preCommandEnabled = preCommandField.text.trim().isNotBlank(),
            preCommand = preCommandField.text.trim(),
            postCommandEnabled = postCommandField.text.trim().isNotBlank(),
            postCommand = postCommandField.text.trim()
        )

        val dialog = MappingEditDialog(null, prefillData = prefill)
        if (dialog.showAndGet()) {
            MappingManager.getInstance().addMapping(dialog.getMappingConfig())
            appendLog("已保存映射: ${dialog.getMappingConfig().name}")
            Messages.showInfoMessage("映射已保存", "保存成功")
        }
    }

    private fun createAction(text: String, icon: Icon, handler: () -> Unit): AnAction {
        return object : AnAction(text, text, icon) {
            override fun actionPerformed(e: AnActionEvent) { handler() }
        }
    }

    private fun createActionButton(text: String, icon: Icon, handler: () -> Unit): JButton {
        return JButton(text, icon).apply { addActionListener { handler() } }
    }
}
