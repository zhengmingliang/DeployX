package com.alianga.idea.deploy.ui.toolwindow

import com.alianga.idea.deploy.action.ActionUtils
import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.DeployItem
import com.alianga.idea.deploy.model.DeployRequest
import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.model.UploadItem
import com.alianga.idea.deploy.model.UpdateReport
import com.alianga.idea.deploy.model.UpdateReportGroup
import com.alianga.idea.deploy.service.DeployService
import com.alianga.idea.deploy.service.HistoryManager
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.service.SyncService
import com.alianga.idea.deploy.service.TerminalService
import com.alianga.idea.deploy.service.UpdateReportFormatter
import com.alianga.idea.deploy.ui.dialog.RemotePathChooserDialog
import com.alianga.idea.deploy.ui.script.ScriptTabPanel
import com.alianga.idea.deploy.ui.settings.MappingEditDialog
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.IconLoader
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
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
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
        /** 按 Project 存储面板实例，供 Action 调用 */
        private val panelByProject = linkedMapOf<String, FileSyncToolWindowPanel>()

        /**
         * “清除日志”图标。直接打包在插件 resources 中引用，
         * 以兼容 AllIcons.Actions.ClearCash 不存在的低版本 IDE（如 IU-241）。
         */
        private val CLEAR_LOG_ICON: Icon =
            IconLoader.getIcon("/icons/clearCash.svg", FileSyncToolWindowPanel::class.java)

        fun getPanel(project: Project): FileSyncToolWindowPanel? {
            return panelByProject[project.hashCode().toString()]
        }
    }

    private val serverManager = ServerManager.getInstance()
    private val deployService = DeployService.getInstance()
    private val historyManager = HistoryManager.getInstance()

    // 操作 tab 组件
    private val serverCombo = JComboBox<String>()
    private val localPathField = JBTextField()
    private val remotePathField = TextFieldWithBrowseButton()
    private val backupCheck = JBCheckBox(DeployXBundle.message("toolwindow.checkbox.backupBeforeDeploy"))
    private val backupDirField = JBTextField()
    private val unzipCheck = JBCheckBox(DeployXBundle.message("toolwindow.checkbox.unzipAfterUpload"))
    private val unzipDestField = JBTextField()
    private val preCommandField = JBTextArea(3, 60)
    private val postCommandField = JBTextArea(3, 60)

    // 操作面板标签（保留引用以便语言切换时刷新文案）
    private val targetServerLabel = JBLabel(DeployXBundle.message("toolwindow.label.targetServer"))
    private val localFileLabel = JBLabel(DeployXBundle.message("toolwindow.label.localFile"))
    private val remotePathLabel = JBLabel(DeployXBundle.message("toolwindow.label.remotePath"))
    private val backupDirLabel = JBLabel(DeployXBundle.message("toolwindow.label.backupDirectory"))
    private val unzipDirLabel = JBLabel(DeployXBundle.message("toolwindow.label.unzipDirectory"))
    private val preCommandLabel = JBLabel(DeployXBundle.message("toolwindow.label.preUploadCommand"))
    private val postCommandLabel = JBLabel(DeployXBundle.message("toolwindow.label.postUploadCommand"))

    // 操作面板按钮（保留引用以便语言切换时刷新文案）
    private val openTerminalButton = createIconButton(DeployXBundle.message("toolwindow.button.openTerminal"), AllIcons.Nodes.Console) { openTerminal() }
    private val previewButton = createActionButton(DeployXBundle.message("toolwindow.button.preview"), AllIcons.Actions.Preview) { previewSync() }
    private val startDeployButton = createActionButton(DeployXBundle.message("toolwindow.button.startDeploy"), AllIcons.Actions.Execute) { startDeploy() }
    private val quickPushButton = createActionButton(DeployXBundle.message("toolwindow.button.quickPush"), AllIcons.Actions.Upload) { quickPush() }
    private val saveAsMappingButton = createActionButton(DeployXBundle.message("toolwindow.button.saveAsMapping"), AllIcons.Actions.MenuSaveall) { saveAsMapping() }

    // 工具栏（保留引用以便语言切换后刷新 Action 显示文本）
    private var toolbar: com.intellij.openapi.actionSystem.ActionToolbar? = null

    // 进度
    private val progressBar = JProgressBar(0, 100)
    private val progressLabel = JBLabel(DeployXBundle.message("toolwindow.progress.ready"))

    // 日志 tab
    private val logArea = JBTextArea()
    private val logTabbedPane = JBTabbedPane()
    private val serverLogAreas = linkedMapOf<String, JBTextArea>()

    // 历史 tab
    private val historyListModel = DefaultListModel<String>()
    private val historyList = JBList(historyListModel)
    private var historyRecords = listOf<HistoryRecord>()
    private val scriptTabPanel = ScriptTabPanel(project)

    // 历史按钮（保留引用以便语言切换时刷新文案）
    private val historyRefreshButton = JButton(DeployXBundle.message("toolwindow.history.refresh"), AllIcons.Actions.Refresh)
    private val historyRedeployButton = JButton(DeployXBundle.message("toolwindow.history.redeploy"), AllIcons.Actions.Execute)
    private val historyFillConfigButton = JButton(DeployXBundle.message("toolwindow.history.fillConfig"), AllIcons.Actions.Edit)
    private val historyCopyReportButton = JButton(DeployXBundle.message("toolwindow.history.copyReport"), AllIcons.Actions.Copy)
    private val historyExportReportButton = JButton(DeployXBundle.message("toolwindow.history.exportReport"), AllIcons.Actions.Download)
    private val historyClearButton = JButton(DeployXBundle.message("toolwindow.history.clear"), AllIcons.Vcs.History)

    // Tab 面板
    private val tabbedPane = JBTabbedPane()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var lastUpdateReport: UpdateReport? = null
    private var lastUpdateReportText: String = ""

    /** 语言变更监听器注销回调，dispose 时调用以避免内存泄漏。 */
    private val languageChangeUnsubscribe: () -> Unit =
        DeployXBundle.addLanguageChangeListener { relocalize() }

    init {
        panelByProject[project.hashCode().toString()] = this
        setupUI()
        setupActions()
        refreshServerCombo()
        refreshHistory()
    }

    private fun setupUI() {
        // 工具栏 Actions（文案通过 bundle key 动态获取，语言切换后由 update() 自动刷新）
        val settingsAction = createLocalizedAction("toolwindow.action.settings", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX")
        }
        val refreshAction = createLocalizedAction("toolwindow.action.refresh", AllIcons.Actions.Refresh) {
            refreshServerCombo()
            refreshHistory()
        }
        val copyReportAction = createLocalizedAction("toolwindow.action.copyReport", AllIcons.Actions.Copy) {
            copyLastReport()
        }
        val exportReportAction = createLocalizedAction("toolwindow.action.exportReport", AllIcons.ToolbarDecorator.Export) {
            exportLastReport()
        }
        val clearLogAction = createLocalizedAction("toolwindow.action.clearLog", CLEAR_LOG_ICON) {
            logArea.text = ""
            serverLogAreas.values.forEach { it.text = "" }
        }
        val actionGroup = DefaultActionGroup().apply {
            listOf(settingsAction, refreshAction, copyReportAction, exportReportAction, clearLogAction).forEach { add(it) }
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("FileSyncToolbar", actionGroup, true)
        toolbar.targetComponent = this
        this.toolbar = toolbar

        // ===== 操作面板 =====
        val serverWithTerminalPanel = JPanel(BorderLayout(6, 0)).apply {
            add(serverCombo, BorderLayout.CENTER)
            add(openTerminalButton, BorderLayout.EAST)
        }
        val serverPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(targetServerLabel, serverWithTerminalPanel)
            .panel

        val filePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(localFileLabel, localPathField)
            .addLabeledComponent(remotePathLabel, remotePathField)
            .panel

        preCommandField.font = Font("Monospaced", Font.PLAIN, 12)
        postCommandField.font = Font("Monospaced", Font.PLAIN, 12)
        preCommandField.lineWrap = true
        postCommandField.lineWrap = true
        preCommandField.wrapStyleWord = true
        postCommandField.wrapStyleWord = true

        val deployPanel = FormBuilder.createFormBuilder()
            .addComponent(backupCheck)
            .addLabeledComponent(backupDirLabel, backupDirField)
            .addComponent(unzipCheck)
            .addLabeledComponent(unzipDirLabel, unzipDestField)
            .addVerticalGap(8)
            .addLabeledComponent(preCommandLabel, JBScrollPane(preCommandField).apply { preferredSize = Dimension(600, 72) })
            .addLabeledComponent(postCommandLabel, JBScrollPane(postCommandField).apply { preferredSize = Dimension(600, 72) })
            .panel

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(previewButton)
            add(Box.createHorizontalStrut(8))
            add(startDeployButton)
            add(Box.createHorizontalStrut(8))
            add(quickPushButton)
            add(Box.createHorizontalStrut(8))
            add(saveAsMappingButton)
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
        logTabbedPane.addTab(DeployXBundle.message("toolwindow.tab.all"), AllIcons.Nodes.LogFolder, JBScrollPane(logArea))

        // ===== 历史面板 =====
        historyList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                showHistoryDetail()
            }
        }

        historyRefreshButton.addActionListener { refreshHistory() }
        historyRedeployButton.addActionListener { redeployFromHistory() }
        historyFillConfigButton.addActionListener { fillFromHistory() }
        historyCopyReportButton.addActionListener { copyReportFromHistory() }
        historyExportReportButton.addActionListener { exportReportFromHistory() }
        historyClearButton.addActionListener { clearHistory() }

        val historyButtonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(historyRefreshButton)
            add(Box.createHorizontalStrut(4))
            add(historyRedeployButton)
            add(Box.createHorizontalStrut(4))
            add(historyFillConfigButton)
            add(Box.createHorizontalStrut(4))
            add(historyCopyReportButton)
            add(Box.createHorizontalStrut(4))
            add(historyExportReportButton)
            add(Box.createHorizontalStrut(8))
            add(historyClearButton)
        }
        val historyPanel = JPanel(BorderLayout()).apply {
            add(historyButtonPanel, BorderLayout.NORTH)
            add(JBScrollPane(historyList), BorderLayout.CENTER)
        }

        // ===== 脚本面板 =====
        scriptTabPanel.setContextProvider { buildScriptRunContext() }
        scriptTabPanel.setLogAppender { serverId, line -> appendLog(serverId, line) }
        scriptTabPanel.setCommandFiller { preCommand, command -> fillCommandFromScript(preCommand, command) }

        // ===== Tab 面板 =====
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.operation"), AllIcons.Actions.Execute, JBScrollPane(operationPanel))
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.log"), AllIcons.Nodes.LogFolder, logTabbedPane)
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.history"), AllIcons.Vcs.History, historyPanel)
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.script"), AllIcons.FileTypes.Xml, scriptTabPanel)

        setContent(tabbedPane)
        setToolbar(toolbar.component)
    }

    /**
     * 语言切换后刷新所有已构建组件的本地化文案。
     *
     * 业务状态（表单输入、选中项、历史记录等）保存在独立字段中，不受影响；
     * 仅更新组件显示文本与 tab 标题。在 EDT 上由 DeployXBundle 监听器触发。
     */
    private fun relocalize() {
        // 操作面板标签
        targetServerLabel.text = DeployXBundle.message("toolwindow.label.targetServer")
        localFileLabel.text = DeployXBundle.message("toolwindow.label.localFile")
        remotePathLabel.text = DeployXBundle.message("toolwindow.label.remotePath")
        backupDirLabel.text = DeployXBundle.message("toolwindow.label.backupDirectory")
        unzipDirLabel.text = DeployXBundle.message("toolwindow.label.unzipDirectory")
        preCommandLabel.text = DeployXBundle.message("toolwindow.label.preUploadCommand")
        postCommandLabel.text = DeployXBundle.message("toolwindow.label.postUploadCommand")

        // 复选框
        backupCheck.text = DeployXBundle.message("toolwindow.checkbox.backupBeforeDeploy")
        unzipCheck.text = DeployXBundle.message("toolwindow.checkbox.unzipAfterUpload")

        // 操作面板按钮
        previewButton.text = DeployXBundle.message("toolwindow.button.preview")
        startDeployButton.text = DeployXBundle.message("toolwindow.button.startDeploy")
        quickPushButton.text = DeployXBundle.message("toolwindow.button.quickPush")
        saveAsMappingButton.text = DeployXBundle.message("toolwindow.button.saveAsMapping")
        openTerminalButton.toolTipText = DeployXBundle.message("toolwindow.button.openTerminal")
        remotePathField.toolTipText = DeployXBundle.message("toolwindow.tooltip.remotePathBrowse")

        // 进度标签：处于空闲"就绪"态（英文 Ready 或中文 就绪）时刷新为新语言文案；
        // 运行中或完成态的动态文案不覆盖，下次操作会重新设置。
        val currentProgressText = progressLabel.text
        if (currentProgressText.isNullOrBlank() || currentProgressText == "Ready" || currentProgressText == "就绪") {
            progressLabel.text = DeployXBundle.message("toolwindow.progress.ready")
        }

        // 工具栏 Actions：每个 Action 在 update() 中按当前语言取文案，
        // 这里触发工具栏刷新即可应用新语言文本。
        toolbar?.updateActionsImmediately()

        // 历史按钮
        historyRefreshButton.text = DeployXBundle.message("toolwindow.history.refresh")
        historyRedeployButton.text = DeployXBundle.message("toolwindow.history.redeploy")
        historyFillConfigButton.text = DeployXBundle.message("toolwindow.history.fillConfig")
        historyCopyReportButton.text = DeployXBundle.message("toolwindow.history.copyReport")
        historyExportReportButton.text = DeployXBundle.message("toolwindow.history.exportReport")
        historyClearButton.text = DeployXBundle.message("toolwindow.history.clear")

        // Tab 标题（operation=0, log=1, history=2, script=3）
        if (tabbedPane.tabCount >= 4) {
            tabbedPane.setTitleAt(0, DeployXBundle.message("toolwindow.tab.operation"))
            tabbedPane.setTitleAt(1, DeployXBundle.message("toolwindow.tab.log"))
            tabbedPane.setTitleAt(2, DeployXBundle.message("toolwindow.tab.history"))
            tabbedPane.setTitleAt(3, DeployXBundle.message("toolwindow.tab.script"))
        }
        // 日志 tab 内的 "All" 子 tab
        if (logTabbedPane.tabCount >= 1) {
            logTabbedPane.setTitleAt(0, DeployXBundle.message("toolwindow.tab.all"))
        }

        // 脚本子面板刷新文案
        scriptTabPanel.relocalize()

        // 刷新工具栏渲染
        revalidate()
        repaint()
    }

    private fun setupActions() {
        backupCheck.addChangeListener { backupDirField.isEnabled = backupCheck.isSelected }
        unzipCheck.addChangeListener { unzipDestField.isEnabled = unzipCheck.isSelected }
        backupDirField.isEnabled = false
        unzipDestField.isEnabled = false

        // 设置远程路径浏览按钮
        remotePathField.addActionListener {
            val selectedServerStr = serverCombo.selectedItem?.toString() ?: return@addActionListener
            val serverId = selectedServerStr.substringBefore(" - ")
            val server = serverManager.getServer(serverId) ?: return@addActionListener

            val currentPath = remotePathField.text.trim().ifBlank { "/" }
            val dialog = RemotePathChooserDialog(server, currentPath)
            if (dialog.showAndGet()) {
                remotePathField.text = dialog.getSelectedPath()
            }
        }
        remotePathField.toolTipText = DeployXBundle.message("toolwindow.tooltip.remotePathBrowse")
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
        appendLog(DeployXBundle.message("toolwindow.log.historySelected", record.summary))
        appendLog(DeployXBundle.message("toolwindow.log.historyServer", record.serverId, record.backupDir.ifBlank { DeployXBundle.message("toolwindow.log.historyNoBackup") }))
        if (record.preCommand.isNotBlank()) appendLog(DeployXBundle.message("toolwindow.log.historyPreCommand", record.preCommand))
        if (record.postCommand.isNotBlank()) appendLog(DeployXBundle.message("toolwindow.log.historyPostCommand", record.postCommand))
    }

    /** 从历史记录重新部署 */
    private fun redeployFromHistory() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.log.selectHistoryFirst"), DeployXBundle.message("toolwindow.log.redeployTitle"))
            return
        }
        val record = historyRecords[idx]
        val file = java.io.File(record.sourcePath)
        if (!file.exists()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.log.localFileNotFound", record.sourcePath), DeployXBundle.message("toolwindow.log.redeployTitle"))
            return
        }

        appendLog(DeployXBundle.message("toolwindow.log.redeploy"))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.deploying")

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
                    progressLabel.text = if (result.success) DeployXBundle.message("toolwindow.progress.deployComplete") else DeployXBundle.message("toolwindow.progress.deployFailed")
                    refreshHistory()
                }
            }
        })
    }

    /** 将历史记录的配置填入操作面板 */
    private fun fillFromHistory() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.log.selectHistoryFirst"), DeployXBundle.message("toolwindow.log.fillConfigTitle"))
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
        appendLog(DeployXBundle.message("toolwindow.log.configFilled"))
    }

    private fun clearHistory() {
        historyManager.clearHistory()
        refreshHistory()
    }

    private fun getSelectedServerId(): String? {
        val selected = serverCombo.selectedItem?.toString() ?: return null
        return selected.substringBefore(" - ")
    }

    fun selectScriptTab() {
        tabbedPane.selectedComponent = scriptTabPanel
        scriptTabPanel.refreshAll()
    }

    private fun buildScriptRunContext(): ScriptRunContext {
        val serverId = getSelectedServerId()
        val server = serverId?.let { serverManager.getServer(it) }
        val localPath = localPathField.text.trim()
        val resolved = localPath.takeIf { it.isNotBlank() }?.let { MappingManager.getInstance().resolveMappingByLocalPath(it) }
        return ScriptRunContext(
            server = server,
            mapping = resolved?.mapping,
            remoteDir = remotePathField.text.trim().ifBlank { resolved?.resolvedRemoteDir },
            localSelectedPaths = localPath.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
            projectBasePath = project.basePath
        )
    }

    private fun fillCommandFromScript(preCommand: Boolean, command: String) {
        val target = if (preCommand) preCommandField else postCommandField
        target.text = if (target.text.isBlank()) {
            command.trim()
        } else {
            target.text.trimEnd() + "\n" + command.trim()
        }
        tabbedPane.selectedIndex = 0
    }

    /**
     * 公开方法：供 Sync / Quick Push 调用 upload-only 批量上传。
     */
    fun executeUploadBatch(items: List<UploadItem>) {
        if (items.isEmpty()) {
            appendLog(DeployXBundle.message("toolwindow.log.noFilesToUpload"))
            return
        }
        appendLog(DeployXBundle.message("toolwindow.log.batchUploadStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.uploading")

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
                    progressLabel.text = DeployXBundle.message("toolwindow.progress.uploadComplete", successCount, results.size)
                    refreshHistory()
                    notifyTransferResult(successCount, results.size)
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用批量部署
     */
    fun executeDeployBatch(items: List<DeployItem>) {
        if (items.isEmpty()) {
            appendLog(DeployXBundle.message("toolwindow.log.noItemsToDeploy"))
            return
        }
        appendLog(DeployXBundle.message("toolwindow.log.batchDeployStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.batchDeploying")

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
                    progressLabel.text = DeployXBundle.message("toolwindow.progress.batchDeployComplete", successCount, results.size)
                    refreshHistory()
                    notifyTransferResult(successCount, results.size)
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用 files-from 批量预览。
     */
    fun executePreviewBatch(items: List<UploadItem>) {
        if (items.isEmpty()) {
            appendLog(DeployXBundle.message("toolwindow.log.noPreviewItems"))
            return
        }
        appendLog(DeployXBundle.message("toolwindow.log.batchPreviewStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.batchPreviewing")

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
                    progressLabel.text = DeployXBundle.message("toolwindow.progress.batchPreviewComplete", successCount, results.size)
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用部署
     */
    fun executeDeploy(request: DeployRequest) {
        appendLog(DeployXBundle.message("toolwindow.log.startDeploy"))
        appendLog(DeployXBundle.message("toolwindow.log.local", request.localPath))
        appendLog(DeployXBundle.message("toolwindow.log.remote", request.serverId, request.remotePath))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.deploying")

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
                    progressLabel.text = if (result.success) DeployXBundle.message("toolwindow.progress.deployComplete") else DeployXBundle.message("toolwindow.progress.deployFailedWithError", result.error ?: "")
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用快速推送
     */
    fun executePush(localPath: String, serverId: String?) {
        appendLog(DeployXBundle.message("toolwindow.log.quickPush"))
        appendLog(DeployXBundle.message("toolwindow.log.local", localPath))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.pushing")

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
                    progressLabel.text = if (result.success) DeployXBundle.message("toolwindow.progress.pushComplete") else DeployXBundle.message("toolwindow.progress.pushFailedWithError", result.error ?: "")
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 公开方法：供右键菜单 Action 调用预览
     */
    fun executePreview(localPath: String, remotePath: String, serverId: String) {
        appendLog(DeployXBundle.message("toolwindow.log.previewSync"))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.previewing")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Previewing Sync...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = SyncService.getInstance().previewSync(localPath, remotePath, serverId) { line ->
                    appendLog(serverId, line)
                }
                SwingUtilities.invokeLater {
                    progressLabel.text = if (result.success) DeployXBundle.message("toolwindow.progress.previewComplete") else DeployXBundle.message("toolwindow.progress.previewFailed")
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
        appendLog(DeployXBundle.message("toolwindow.report.generated"))
    }

    /**
     * 批量传输完成后弹出系统通知。
     */
    private fun notifyTransferResult(successCount: Int, total: Int) {
        val failCount = total - successCount
        val (message, type) = when {
            failCount == 0 -> DeployXBundle.message("notification.transfer.allSuccess", successCount) to NotificationType.INFORMATION
            successCount == 0 -> DeployXBundle.message("notification.transfer.allFailed", total) to NotificationType.ERROR
            else -> DeployXBundle.message("notification.transfer.partial", successCount, failCount) to NotificationType.WARNING
        }
        ActionUtils.showSystemNotification(project, "DeployX", message, type)
    }

    private fun copyLastReport() {
        if (lastUpdateReportText.isBlank()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.report.noReportToCopy"), DeployXBundle.message("toolwindow.report.copy.title"))
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(lastUpdateReportText))
        Messages.showInfoMessage(DeployXBundle.message("toolwindow.report.copied"), DeployXBundle.message("toolwindow.report.copy.title"))
    }

    private fun exportLastReport() {
        if (lastUpdateReportText.isBlank()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.report.noReportToExport"), DeployXBundle.message("toolwindow.report.export.title"))
            return
        }
        val chooser = JFileChooser().apply {
            selectedFile = java.io.File("file-sync-report-${System.currentTimeMillis()}.md")
        }
        val result = chooser.showSaveDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.writeText(lastUpdateReportText)
            Messages.showInfoMessage(DeployXBundle.message("toolwindow.report.exported", chooser.selectedFile.absolutePath), DeployXBundle.message("toolwindow.report.export.title"))
        }
    }

    /**
     * 从选中的历史记录复制报告到剪贴板。
     * 历史记录的 reportText 在创建时已预格式化为 Markdown。
     */
    private fun copyReportFromHistory() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.log.selectHistoryFirst"), DeployXBundle.message("toolwindow.report.copy.title"))
            return
        }
        val reportText = historyRecords[idx].reportText
        if (reportText.isBlank()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.report.noReportToCopy"), DeployXBundle.message("toolwindow.report.copy.title"))
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(reportText))
        Messages.showInfoMessage(DeployXBundle.message("toolwindow.report.copied"), DeployXBundle.message("toolwindow.report.copy.title"))
    }

    /**
     * 从选中的历史记录导出报告到文件。
     */
    private fun exportReportFromHistory() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.log.selectHistoryFirst"), DeployXBundle.message("toolwindow.report.export.title"))
            return
        }
        val reportText = historyRecords[idx].reportText
        if (reportText.isBlank()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.report.noReportToExport"), DeployXBundle.message("toolwindow.report.export.title"))
            return
        }
        val record = historyRecords[idx]
        val chooser = JFileChooser().apply {
            selectedFile = java.io.File("deployx-report-${record.formattedDate.replace("[ :/]".toRegex(), "-")}.md")
        }
        val result = chooser.showSaveDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.writeText(reportText)
            Messages.showInfoMessage(DeployXBundle.message("toolwindow.report.exported", chooser.selectedFile.absolutePath), DeployXBundle.message("toolwindow.report.export.title"))
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
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.fillSyncInfo"), "Preview Sync")
            return
        }

        appendLog(DeployXBundle.message("toolwindow.log.previewSync"))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.previewing")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Previewing Sync...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = DeployXBundle.message("toolwindow.progress.previewing")
                val result = SyncService.getInstance().previewSync(localPath, remotePath, serverId) { line ->
                    appendLog(serverId, line)
                }
                SwingUtilities.invokeLater {
                    progressLabel.text = if (result.success) DeployXBundle.message("toolwindow.progress.previewComplete") else DeployXBundle.message("toolwindow.progress.previewFailed")
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
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.fillDeployInfo"), "Deploy")
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

        appendLog(DeployXBundle.message("toolwindow.log.startDeploy"))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.deploying")

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
                    progressLabel.text = if (result.success) DeployXBundle.message("toolwindow.progress.deployComplete") else DeployXBundle.message("toolwindow.progress.deployFailed")
                    refreshHistory()
                }
            }
        })
    }

    private fun quickPush() {
        val localPath = localPathField.text.trim()
        val serverId = getSelectedServerId()

        if (localPath.isEmpty()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.fillLocalPath"), "Quick Push")
            return
        }

        appendLog(DeployXBundle.message("toolwindow.log.quickPush"))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.pushing")

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
                    progressLabel.text = if (result.success) DeployXBundle.message("toolwindow.progress.pushComplete") else DeployXBundle.message("toolwindow.progress.pushFailed")
                    refreshHistory()
                }
            }
        })
    }

    /**
     * 打开 SSH 终端连接到当前选中的服务器
     */
    private fun openTerminal() {
        val serverId = getSelectedServerId()
        if (serverId == null) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.selectServerFirst"), DeployXBundle.message("toolwindow.log.openTerminalTitle"))
            return
        }

        val server = serverManager.getServer(serverId)
        if (server == null) {
            Messages.showErrorDialog(DeployXBundle.message("toolwindow.validation.serverNotFound"), DeployXBundle.message("toolwindow.log.openTerminalTitle"))
            return
        }

        if (!TerminalService.getInstance().openTerminal(project, server)) {
            Messages.showErrorDialog(DeployXBundle.message("toolwindow.validation.cannotOpenTerminal"), DeployXBundle.message("toolwindow.log.openTerminalTitle"))
        }
    }

    /**
     * 将当前操作面板的配置保存为目录映射
     */
    private fun saveAsMapping() {
        val serverId = getSelectedServerId()
        if (serverId == null) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.selectServerFirst"), DeployXBundle.message("toolwindow.log.saveAsMappingTitle"))
            return
        }
        val localPath = localPathField.text.trim()
        val remotePath = remotePathField.text.trim()
        if (localPath.isBlank() || remotePath.isBlank()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.fillLocalAndRemote"), DeployXBundle.message("toolwindow.log.saveAsMappingTitle"))
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
            appendLog(DeployXBundle.message("toolwindow.log.mappedSaved", dialog.getMappingConfig().name))
            Messages.showInfoMessage(DeployXBundle.message("toolwindow.validation.mappingSaved"), DeployXBundle.message("toolwindow.validation.saveSuccess"))
        }
    }

    private fun createAction(text: String, icon: Icon, handler: () -> Unit): AnAction {
        return object : AnAction(text, text, icon) {
            override fun actionPerformed(e: AnActionEvent) { handler() }
        }
    }

    /**
     * 创建工具栏 Action，文案通过 bundle key 在 [update] 中动态获取，
     * 使语言切换后无需重建即可刷新显示文本。
     */
    private fun createLocalizedAction(textKey: String, icon: Icon, handler: () -> Unit): AnAction {
        return object : AnAction(DeployXBundle.message(textKey), DeployXBundle.message(textKey), icon) {
            override fun actionPerformed(e: AnActionEvent) { handler() }
            override fun update(e: AnActionEvent) {
                e.presentation.text = DeployXBundle.message(textKey)
                e.presentation.description = DeployXBundle.message(textKey)
            }
        }
    }

    private fun createActionButton(text: String, icon: Icon, handler: () -> Unit): JButton {
        return JButton(text, icon).apply { addActionListener { handler() } }
    }

    private fun createIconButton(toolTip: String, icon: Icon, handler: () -> Unit): JButton {
        return JButton(icon).apply {
            this.toolTipText = toolTip
            isFocusable = false
            addActionListener { handler() }
        }
    }
}
