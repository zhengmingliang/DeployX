package com.alianga.idea.deploy.ui.toolwindow

import com.alianga.idea.deploy.action.ActionUtils
import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.DeployItem
import com.alianga.idea.deploy.model.DeployRequest
import com.alianga.idea.deploy.model.DownloadItem
import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.model.UploadItem
import com.alianga.idea.deploy.model.UpdateReport
import com.alianga.idea.deploy.model.UpdateReportGroup
import com.alianga.idea.deploy.service.DeployCancelToken
import com.alianga.idea.deploy.service.DeployCancelledException
import com.alianga.idea.deploy.service.DeployService
import com.alianga.idea.deploy.service.HistoryManager
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.service.SyncService
import com.alianga.idea.deploy.service.TerminalService
import com.alianga.idea.deploy.service.UpdateReportFormatter
import com.alianga.idea.deploy.ui.CommandFieldWithScriptButton
import com.alianga.idea.deploy.ui.UiButtonFactory
import com.alianga.idea.deploy.ui.dialog.HistoryDetailDialog
import com.alianga.idea.deploy.ui.dialog.RemotePathChooserDialog
import com.alianga.idea.deploy.ui.dialog.RollbackDialog
import com.alianga.idea.deploy.ui.dialog.RollbackProgressDialog
import com.alianga.idea.deploy.ui.script.ScriptTabPanel
import com.alianga.idea.deploy.ui.settings.MappingEditDialog
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
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
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
         * “清除日志”图标。使用平台 AllIcons.Actions.GC（垃圾桶），
         * 语义贴合且颜色随主题自适应，与工具栏其他 AllIcons 图标风格统一。
         */
        private val CLEAR_LOG_ICON: Icon = AllIcons.Actions.GC

        fun getPanel(project: Project): FileSyncToolWindowPanel? {
            return panelByProject[project.hashCode().toString()]
        }

        /** 重新应用日志字体大小到所有已打开的工具窗口面板（设置变更后调用）。 */
        fun reapplyLogFontAll() {
            panelByProject.values.forEach { it.reapplyLogFont() }
        }
    }

    private val serverManager = ServerManager.getInstance()
    private val deployService = DeployService.getInstance()
    private val historyManager = HistoryManager.getInstance()

    // 操作 tab 组件
    private val serverCombo = JComboBox<String>()
    // 1.0.8：本地路径改为 TextFieldWithBrowseButton，支持选目录（与选文件并存）
    private val localPathField = TextFieldWithBrowseButton()
    private val remotePathField = TextFieldWithBrowseButton()
    private val backupCheck = JBCheckBox(DeployXBundle.message("toolwindow.checkbox.backupBeforeDeploy"))
    private val backupDirField = JBTextField()
    private val unzipCheck = JBCheckBox(DeployXBundle.message("toolwindow.checkbox.unzipAfterUpload"))
    private val unzipDestField = JBTextField()
    private val preCommandField = CommandFieldWithScriptButton(
        project = project,
        contextProvider = { buildScriptRunContext() },
        multiline = true,
        preferredScrollSize = Dimension(600, 72)
    )
    private val postCommandField = CommandFieldWithScriptButton(
        project = project,
        contextProvider = { buildScriptRunContext() },
        multiline = true,
        preferredScrollSize = Dimension(600, 72)
    )

    // 操作面板标签（保留引用以便语言切换时刷新文案）
    private val targetServerLabel = JBLabel(DeployXBundle.message("toolwindow.label.targetServer"))
    private val localFileLabel = JBLabel(DeployXBundle.message("toolwindow.label.localFile"))
    private val remotePathLabel = JBLabel(DeployXBundle.message("toolwindow.label.remotePath"))
    private val backupDirLabel = JBLabel(DeployXBundle.message("toolwindow.label.backupDirectory"))
    private val unzipDirLabel = JBLabel(DeployXBundle.message("toolwindow.label.unzipDirectory"))
    private val preCommandLabel = JBLabel(DeployXBundle.message("toolwindow.label.preUploadCommand"))
    private val postCommandLabel = JBLabel(DeployXBundle.message("toolwindow.label.postUploadCommand"))

    // 操作面板按钮（保留引用以便语言切换时刷新文案）
    private val openTerminalButton = UiButtonFactory.createIconButton(DeployXBundle.message("toolwindow.button.openTerminal"), AllIcons.Nodes.Console) { openTerminal() }
    private val browseRemoteButton = UiButtonFactory.createIconButton(DeployXBundle.message("toolwindow.button.browseRemote"), AllIcons.Nodes.Folder) { browseRemote() }
    private val previewButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.preview"), AllIcons.Actions.Preview) { previewSync() }
    // 1.0.8 新增：预览拉取按钮
    private val previewPullButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.previewPull"), AllIcons.Actions.Preview) { previewPull() }
    private val startDeployButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.startDeploy"), AllIcons.Actions.Execute) { startDeploy() }
    // 1.0.8 新增：拉取按钮（从服务器下载到本地）
    private val pullButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.pull"), AllIcons.Actions.Download) { pullFromServer() }
    private val quickPushButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.quickPush"), AllIcons.Actions.Upload) { quickPush() }
    private val saveAsMappingButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.saveAsMapping"), AllIcons.Actions.MenuSaveall) { saveAsMapping() }
    /**
     * 终止按钮（1.0.6 新增，运行中可点击终止当前部署/同步操作，初始禁用）。
     * 1.0.8 调整：从操作面板进度区迁移到日志面板顶部，使其在所有执行动作中
     * 始终可见且与日志就近展示。
     */
    private val abortButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.abort"), AllIcons.Actions.Suspend) { abortCurrentTask() }

    /** 当前运行中任务的取消令牌（1.0.6 新增）。null 表示无任务运行。 */
    @Volatile
    private var currentCancelToken: DeployCancelToken? = null

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

    // 历史按钮：使用 AnAction + ActionToolbar（与顶部工具栏同一机制），
    // 间距由平台统一控制，紧凑且一致；按钮以图标显示，文案与说明通过 tooltip/description 呈现。
    // 回滚按钮图标与历史列表中“可回滚”记录的图标一致（均为 AllIcons.Actions.Rollback）。
    private val historyRefreshAction = UiButtonFactory.createLocalizedAction("toolwindow.history.refresh", AllIcons.Actions.Refresh, "toolwindow.history.refresh.tooltip") { refreshHistory() }
    private val historyRedeployAction = UiButtonFactory.createLocalizedAction("toolwindow.history.redeploy", AllIcons.Actions.Execute, "toolwindow.history.redeploy.tooltip") { redeployFromHistory() }
    private val historyFillConfigAction = UiButtonFactory.createLocalizedAction("toolwindow.history.fillConfig", AllIcons.Actions.Edit, "toolwindow.history.fillConfig.tooltip") { fillFromHistory() }
    private val historyCopyReportAction = UiButtonFactory.createLocalizedAction("toolwindow.history.copyReport", AllIcons.Actions.Copy, "toolwindow.history.copyReport.tooltip") { copyReportFromHistory() }
    private val historyExportReportAction = UiButtonFactory.createLocalizedAction("toolwindow.history.exportReport", AllIcons.Actions.Download, "toolwindow.history.exportReport.tooltip") { exportReportFromHistory() }
    private val historyRollbackAction = UiButtonFactory.createLocalizedAction("toolwindow.history.rollback", AllIcons.Actions.Rollback, "toolwindow.history.rollback.tooltip") { rollbackFromHistory() }
    private val historyViewDetailAction = UiButtonFactory.createLocalizedAction("toolwindow.history.viewDetail", AllIcons.Actions.Preview, "toolwindow.history.viewDetail.tooltip") { viewHistoryDetail() }
    private val historyClearAction = UiButtonFactory.createLocalizedAction("toolwindow.history.clear", AllIcons.Actions.GC, "toolwindow.history.clear.tooltip") { clearHistory() }
    /** 历史工具栏（与顶部工具栏同机制，间距由平台统一控制） */
    private var historyToolbar: ActionToolbar? = null

    /** 历史列表为空时的占位提示（替代原先的空白，明确告知用户“暂无记录”） */
    private val historyEmptyLabel = JBLabel(DeployXBundle.message("toolwindow.history.empty"), SwingConstants.CENTER)
    /** 历史面板的卡片布局：有记录显示列表，无记录显示占位提示 */
    private val historyCardLayout = CardLayout()
    private lateinit var historyCardPanel: JPanel

    // Tab 面板
    private val tabbedPane = JBTabbedPane()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var lastUpdateReport: UpdateReport? = null
    private var lastUpdateReportText: String = ""

    /** 语言变更监听器注销回调，dispose 时调用以避免内存泄漏。 */
    private val languageChangeUnsubscribe: () -> Unit =
        DeployXBundle.addLanguageChangeListener { relocalize() }

    /**
     * 服务器列表变更监听器注销回调（1.0.6 新增）。
     * ServerManager 增删改服务器后触发，在 EDT 上刷新目标服务器下拉，
     * 使设置页新增/删除的服务器无需重启 IDE 即可出现在侧边栏。
     */
    private val serverListUnsubscribe: () -> Unit =
        serverManager.addChangeListener {
            SwingUtilities.invokeLater { refreshServerCombo() }
        }

    init {
        panelByProject[project.hashCode().toString()] = this
        setupUI()
        setupActions()
        refreshServerCombo()
        refreshHistory()
    }

    private fun setupUI() {
        // 工具栏 Actions（文案通过 bundle key 动态获取，语言切换后由 update() 自动刷新）
        val settingsAction = UiButtonFactory.createLocalizedAction("toolwindow.action.settings", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX")
        }
        val refreshAction = UiButtonFactory.createLocalizedAction("toolwindow.action.refresh", AllIcons.Actions.Refresh) {
            refreshServerCombo()
            refreshHistory()
        }
        val copyReportAction = UiButtonFactory.createLocalizedAction("toolwindow.action.copyReport", AllIcons.Actions.Copy) {
            copyLastReport()
        }
        val exportReportAction = UiButtonFactory.createLocalizedAction("toolwindow.action.exportReport", AllIcons.ToolbarDecorator.Export) {
            exportLastReport()
        }
        val clearLogAction = UiButtonFactory.createLocalizedAction("toolwindow.action.clearLog", CLEAR_LOG_ICON) {
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
        val serverButtonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(browseRemoteButton)
            add(Box.createHorizontalStrut(4))
            add(openTerminalButton)
        }
        val serverWithTerminalPanel = JPanel(BorderLayout(6, 0)).apply {
            add(serverCombo, BorderLayout.CENTER)
            add(serverButtonsPanel, BorderLayout.EAST)
        }
        val serverPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(targetServerLabel, serverWithTerminalPanel)
            .panel

        val filePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(localFileLabel, localPathField)
            .addLabeledComponent(remotePathLabel, remotePathField)
            .panel

        val deployPanel = FormBuilder.createFormBuilder()
            .addComponent(backupCheck)
            .addLabeledComponent(backupDirLabel, backupDirField)
            .addComponent(unzipCheck)
            .addLabeledComponent(unzipDirLabel, unzipDestField)
            .addVerticalGap(8)
            .addLabeledComponent(preCommandLabel, preCommandField)
            .addLabeledComponent(postCommandLabel, postCommandField)
            .panel

        // 1.0.8 调整：6 个按钮拥挤在一行不便点击，拆为两行
        // 第 1 行：预览 | 预览拉取 | 部署 | 拉取（preview 与 deploy 一一对应）
        // 第 2 行：快速推送 | 保存为映射（辅助动作）
        val buttonRow1 = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(previewButton)
            add(Box.createHorizontalStrut(8))
            add(previewPullButton)
            add(Box.createHorizontalStrut(8))
            add(startDeployButton)
            add(Box.createHorizontalStrut(8))
            add(pullButton)
        }
        val buttonRow2 = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(quickPushButton)
            add(Box.createHorizontalStrut(8))
            add(saveAsMappingButton)
        }
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(buttonRow1)
            add(Box.createVerticalStrut(6))
            add(buttonRow2)
            // 整体左对齐，避免被 FormBuilder/外层拉伸填满
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // 进度面板：进度条 + 状态标签（1.0.8 调整：移除终止按钮，迁至日志面板顶部，
        // 使终止操作在所有执行动作中始终可见且与日志就近展示）
        val progressInfoPanel = JPanel(BorderLayout(4, 0)).apply {
            add(progressBar, BorderLayout.CENTER)
            add(progressLabel, BorderLayout.EAST)
        }
        val progressPanel = JPanel(BorderLayout()).apply {
            add(progressInfoPanel, BorderLayout.CENTER)
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
        // 1.0.8：每个 Tab 内部顶部加「复制此 Tab 日志」按钮，logArea 对应"全部" Tab（serverId=null）
        logTabbedPane.addTab(DeployXBundle.message("toolwindow.tab.all"), AllIcons.Nodes.LogFolder, buildLogTabContent(logArea, null))
        // 1.0.8：日志面板顶部加工具栏（终止按钮放在最右侧），始终可见
        val logHeaderPanel = JPanel(BorderLayout()).apply {
            add(buildLogHeaderCopyBar(), BorderLayout.WEST)
            add(abortButton, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        val logPanel = JPanel(BorderLayout()).apply {
            add(logHeaderPanel, BorderLayout.NORTH)
            add(logTabbedPane, BorderLayout.CENTER)
        }

        // ===== 历史面板 =====
        // 自定义渲染器：可回滚的记录显示回滚图标，与「回滚」按钮图标保持一致，
        // 便于用户快速识别哪些历史记录可以回滚。
        historyList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (index >= 0 && index < historyRecords.size) {
                    val record = historyRecords[index]
                    if (record.canRollback && record.backupFilePath.isNotBlank()) {
                        // 使用与回滚按钮相同的 AllIcons.Actions.Rollback 图标
                        icon = AllIcons.Actions.Rollback
                    } else {
                        icon = null
                    }
                }
                return component
            }
        }

        // 双击历史记录弹出详情对话框（展示更新文件清单等），不再跳转日志页签
        historyList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val idx = historyList.locationToIndex(e.point)
                    if (idx >= 0) {
                        historyList.selectedIndex = idx
                        viewHistoryDetail()
                    }
                }
            }
        })

        // 历史工具栏：与顶部工具栏同一机制（DefaultActionGroup + ActionToolbar），
        // 间距由平台统一控制，紧凑一致；回滚与清空之间插入 Separator 作为危险操作分组。
        val historyActionGroup = DefaultActionGroup().apply {
            add(historyRefreshAction)
            add(historyRedeployAction)
            add(historyFillConfigAction)
            add(historyCopyReportAction)
            add(historyExportReportAction)
            add(historyRollbackAction)
            add(historyViewDetailAction)
            addSeparator()
            add(historyClearAction)
        }
        val historyToolbarInstance = ActionManager.getInstance().createActionToolbar("FileSyncHistoryToolbar", historyActionGroup, true)
        historyToolbarInstance.targetComponent = this
        historyToolbar = historyToolbarInstance
        val historyButtonPanel = JPanel(BorderLayout()).apply {
            add(historyToolbarInstance.component, BorderLayout.CENTER)
        }
        historyCardPanel = JPanel(historyCardLayout).apply {
            add(JBScrollPane(historyList), "list")
            add(historyEmptyLabel, "empty")
        }
        val historyPanel = JPanel(BorderLayout()).apply {
            add(historyButtonPanel, BorderLayout.NORTH)
            add(historyCardPanel, BorderLayout.CENTER)
        }

        // ===== 脚本面板 =====
        scriptTabPanel.setContextProvider { buildScriptRunContext() }
        scriptTabPanel.setLogAppender { serverId, line -> appendLog(serverId, line) }
        scriptTabPanel.setCommandFiller { preCommand, command -> fillCommandFromScript(preCommand, command) }

        // ===== Tab 面板 =====
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.operation"), AllIcons.Actions.Execute, JBScrollPane(operationPanel))
        // 1.0.8：日志 Tab 容器已包裹"终止按钮 + 复制按钮"工具栏，传 logPanel
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.log"), AllIcons.Nodes.LogFolder, logPanel)
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.history"), AllIcons.Vcs.History, historyPanel)
        tabbedPane.addTab(DeployXBundle.message("toolwindow.tab.script"), AllIcons.FileTypes.Xml, scriptTabPanel)

        setContent(tabbedPane)
        setToolbar(toolbar.component)

        // 切换到「历史」Tab 时重新加载，确保展示最新记录（避免工具窗口初次构建时
        // HistoryManager 尚未就绪导致列表一直为空、显示空白的问题）
        tabbedPane.addChangeListener {
            if (tabbedPane.selectedComponent === historyPanel) {
                refreshHistory()
            }
        }
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
        abortButton.text = DeployXBundle.message("toolwindow.button.abort")
        abortButton.toolTipText = DeployXBundle.message("toolwindow.button.abort.tooltip")
        openTerminalButton.toolTipText = DeployXBundle.message("toolwindow.button.openTerminal")
        browseRemoteButton.toolTipText = DeployXBundle.message("toolwindow.button.browseRemote")
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

        // 历史工具栏 Actions：每个 Action 在 update() 中按当前语言取文案，
        // 触发工具栏刷新即可应用新语言文本。
        historyToolbar?.updateActionsImmediately()
        historyEmptyLabel.text = DeployXBundle.message("toolwindow.history.empty")

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

        // 刷新脚本选择按钮的 tooltip
        preCommandField.updateTooltip()
        postCommandField.updateTooltip()

        // 刷新工具栏渲染
        revalidate()
        repaint()
    }

    private fun setupActions() {
        backupCheck.addChangeListener { backupDirField.isEnabled = backupCheck.isSelected }
        unzipCheck.addChangeListener { unzipDestField.isEnabled = unzipCheck.isSelected }
        backupDirField.isEnabled = false
        unzipDestField.isEnabled = false

        // 终止按钮初始禁用（无任务运行时不可点击）
        abortButton.isEnabled = false
        abortButton.toolTipText = DeployXBundle.message("toolwindow.button.abort.tooltip")

        // 1.0.8：本地路径浏览按钮，支持选文件或选目录（FileChooserDescriptor 通过
        // withFileFilter 接受 null + isForcedToShowFiles = false 实现二者皆可）
        setupLocalPathBrowser()

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

    /**
     * 1.0.8：本地路径浏览按钮。允许用户选择文件或目录（操作面板中"本地文件"也支持
     * 选择目录，部署一个目录时直接按目录方式上传）。使用 [com.intellij.openapi.fileChooser.FileChooserFactory]
     * 自定义 descriptor，关闭文件过滤、显示隐藏文件选项、允许选目录。
     */
    private fun setupLocalPathBrowser() {
        localPathField.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor()
                .withTitle(DeployXBundle.message("toolwindow.button.browseLocal"))
                .withDescription(DeployXBundle.message("toolwindow.button.browseLocal"))
                .withShowHiddenFiles(false)
            val fileChooser = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                .createFileChooser(descriptor, project, localPathField)
            // 1.0.8：尝试以当前路径为起点；如不存在则使用项目根或用户主目录
            val initialFile = runCatching {
                val current = localPathField.text.trim()
                if (current.isNotEmpty()) {
                    val f = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(
                        if (java.io.File(current).exists()) java.io.File(current)
                        else (project?.basePath?.let { java.io.File(it) } ?: java.io.File(System.getProperty("user.home")))
                    )
                    f
                } else {
                    project?.basePath?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(java.io.File(it)) }
                }
            }.getOrNull()
            val chosen = fileChooser.choose(project, initialFile)
            if (chosen.isNotEmpty()) {
                localPathField.text = chosen.first().path
            }
        }
        localPathField.toolTipText = DeployXBundle.message("toolwindow.button.browseLocal")
    }

    /** 日志面板顶部复制按钮工具栏（"复制全部日志"）。1.0.8 新增。 */
    private fun buildLogHeaderCopyBar(): JPanel {
        val copyAllButton = JButton(DeployXBundle.message("toolwindow.action.copyAllLog"), AllIcons.Actions.Copy)
        copyAllButton.toolTipText = DeployXBundle.message("toolwindow.action.copyAllLog.tooltip")
        copyAllButton.addActionListener { copyLogText(logArea.text) }
        val bar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(copyAllButton)
        }
        return bar
    }

    /**
     * 构造一个日志 Tab 的容器：顶部"复制"按钮 + 下方滚动文本区。
     * 1.0.8 修复：每个服务器分组 Tab 现在可独立复制自己的日志。
     * @param serverId 此 Tab 对应的服务器 ID（"全部" Tab 传 null）
     */
    private fun buildLogTabContent(area: JBTextArea, serverId: String?): JPanel {
        val copyButton = JButton(
            DeployXBundle.message("toolwindow.action.copyLog"),
            AllIcons.Actions.Copy
        ).apply {
            toolTipText = DeployXBundle.message("toolwindow.action.copyLog.tooltip")
            addActionListener { copyLogText(area.text) }
        }
        val top = JPanel(BorderLayout()).apply {
            add(copyButton, BorderLayout.WEST)
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
        }
    }

    /** 1.0.8：复制日志文本到剪贴板并提示用户。空内容给出友好提示。 */
    private fun copyLogText(text: String) {
        if (text.isBlank()) {
            Messages.showInfoMessage(project, DeployXBundle.message("toolwindow.report.noReportToCopy"), DeployXBundle.message("toolwindow.action.copyLog"))
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Messages.showInfoMessage(project, DeployXBundle.message("toolwindow.report.copied"), DeployXBundle.message("toolwindow.action.copyLog"))
    }

    /**
     * 终止当前正在运行的部署/同步任务（1.0.6 新增）。
     * 触发 [currentCancelToken] 取消，传输层会在下一个检查点抛 [DeployCancelledException] 停止。
     */
    private fun abortCurrentTask() {
        val token = currentCancelToken ?: return
        token.cancel()
        appendLog(DeployXBundle.message("toolwindow.log.abortRequested"))
        abortButton.isEnabled = false
    }

    /**
     * 启动一个可取消的后台任务（1.0.6 新增）。
     *
     * 统一管理 [DeployCancelToken] 的生命周期与终止按钮的启用/禁用：
     * - 任务开始前创建 token、启用终止按钮
     * - 任务结束后（无论成功/失败/终止）禁用终止按钮、清空 token
     * - [taskBody] 的返回值会传递给 [onProgressDone]（在 EDT 上执行），便于更新 UI
     *
     * **IDEA 进程窗口取消联动**：[Task.Backgroundable] 设置了 canBeCancelled=true，
     * 用户点击 IDEA 进度窗口的取消按钮会触发 [ProgressIndicator.cancel]。
     * 本方法在 [run] 中轮询 [ProgressIndicator.isCanceled]，一旦检测到取消
     * 立即调用 [DeployCancelToken.cancel]，使传输层尽快停止，不再需要用户额外点击"终止"按钮。
     *
     * @param title 后台任务标题
     * @param onProgressDone EDT 上的完成回调，接收 (取消令牌, taskBody 返回值)
     * @param taskBody 后台线程执行体，接收取消令牌，返回结果给 onProgressDone
     */
    private fun <T> launchCancelableTask(
        title: String,
        onProgressDone: (DeployCancelToken, T) -> Unit,
        taskBody: (DeployCancelToken) -> T
    ) {
        val cancelToken = DeployCancelToken()
        currentCancelToken = cancelToken
        abortButton.isEnabled = true

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var result: T? = null

            override fun run(indicator: ProgressIndicator) {
                // 启动一个轻量守护线程轮询 IDEA 的 ProgressIndicator 取消状态，
                // 一旦用户点击 IDEA 进程窗口的取消按钮，立即联动触发 cancelToken。
                val indicatorWatcher = Thread({
                    while (!Thread.currentThread().isInterrupted) {
                        if (indicator.isCanceled && !cancelToken.isCancelled()) {
                            cancelToken.cancel()
                            return@Thread
                        }
                        Thread.sleep(150)
                    }
                }, "DeployX-cancel-watcher").apply { isDaemon = true }
                indicatorWatcher.start()

                try {
                    result = taskBody(cancelToken)
                } catch (e: DeployCancelledException) {
                    // 任务被用户终止（终止按钮或 IDEA 进程取消），已由各服务层处理为"已终止"结果
                } finally {
                    indicatorWatcher.interrupt()
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun onFinished() {
                SwingUtilities.invokeLater {
                    abortButton.isEnabled = false
                    currentCancelToken = null
                    onProgressDone(cancelToken, result as T)
                }
            }
        })
    }

    private fun refreshServerCombo() {
        serverCombo.removeAllItems()
        val servers = serverManager.getServers()
        for (server in servers) {
            // LOCAL 服务器追加 [本地] 标记便于识别
            val typeMark = if (server.isLocal) " [${DeployXBundle.message("dialog.server.type.local")}]" else ""
            serverCombo.addItem("${server.id} - ${server.name}$typeMark")
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
        // 根据是否有记录切换卡片：有记录显示列表，无记录显示占位提示
        historyCardLayout.show(historyCardPanel, if (historyRecords.isEmpty()) "empty" else "list")
    }

    /**
     * 弹出历史详情对话框：展示选中记录的关键信息与更新文件清单。
     * 双击历史记录或点击「查看详情」按钮触发，关闭即消失，不占用常驻空间。
     */
    private fun viewHistoryDetail() {
        val idx = historyList.selectedIndex
        if (idx < 0 || idx >= historyRecords.size) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.log.selectHistoryFirst"), DeployXBundle.message("history.detail.dialog.title"))
            return
        }
        val record = historyRecords[idx]
        HistoryDetailDialog(project, record).show()
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
        // 1.0.8：操作执行前自动切到日志页
        switchToLogTab()
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.deploying")

        launchCancelableTask(
            "Redeploying...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.DeployResult ->
                progressBar.value = if (result.success) 100 else progressBar.value
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.deployComplete")
                    else -> DeployXBundle.message("toolwindow.progress.deployFailed")
                }
                refreshHistory()
            },
            taskBody = { cancelToken ->
                deployService.redeploy(
                    record,
                    logCallback = { line -> appendLog(record.serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

        /** 从历史记录执行回滚 */
        private fun rollbackFromHistory() {
            val idx = historyList.selectedIndex
            if (idx < 0 || idx >= historyRecords.size) {
                Messages.showWarningDialog(
                    DeployXBundle.message("toolwindow.log.selectHistoryFirst"),
                    DeployXBundle.message("toolwindow.log.rollbackTitle")
                )
                return
            }
            val record = historyRecords[idx]

            // 检查是否可回滚
            if (!record.canRollback || record.backupFilePath.isBlank()) {
                Messages.showWarningDialog(
                    DeployXBundle.message("toolwindow.log.rollbackNotAvailable"),
                    DeployXBundle.message("toolwindow.log.rollbackTitle")
                )
                return
            }

            // 显示回滚确认对话框
            val dialog = RollbackDialog(project, record)
            if (!dialog.showAndGet()) {
                return
            }

            // 执行回滚
            val progressDialog = RollbackProgressDialog(project, record) { result ->
                if (result.success) {
                    appendLog(DeployXBundle.message("toolwindow.log.rollbackSuccess"))
                    appendLog(DeployXBundle.message("toolwindow.log.restoredFiles", result.rolledBackFiles.size))
                    ActionUtils.showNotification(
                        project,
                        DeployXBundle.message("toolwindow.notification.rollbackSuccess"),
                        NotificationType.INFORMATION
                    )
                } else {
                    appendLog(DeployXBundle.message("toolwindow.log.rollbackFailed", result.error ?: ""))
                    ActionUtils.showNotification(
                        project,
                        DeployXBundle.message("toolwindow.notification.rollbackFailed", result.error ?: ""),
                        NotificationType.ERROR
                    )
                }
                refreshHistory()
            }
            progressDialog.show()
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
        // 1.0.8：操作执行前自动切到日志页
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.batchUploadStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.uploading")

        launchCancelableTask(
            "Batch Uploading...",
            onProgressDone = { token, results: List<com.alianga.idea.deploy.model.SyncResult> ->
                val successCount = results.count { it.success }
                updateLastReport("UPLOAD", results.mapNotNull { it.reportGroup })
                progressBar.value = 100
                progressLabel.text = if (token.isCancelled())
                    DeployXBundle.message("toolwindow.progress.aborted")
                else
                    DeployXBundle.message("toolwindow.progress.uploadComplete", successCount, results.size)
                refreshHistory()
                if (!token.isCancelled()) notifyTransferResult(successCount, results.size)
            },
            taskBody = { cancelToken ->
                deployService.uploadBatch(
                    items,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 公开方法：供右键菜单 Action 调用批量部署
     */
    fun executeDeployBatch(items: List<DeployItem>) {
        if (items.isEmpty()) {
            appendLog(DeployXBundle.message("toolwindow.log.noItemsToDeploy"))
            return
        }
        // 1.0.8：操作执行前自动切到日志页
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.batchDeployStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.batchDeploying")

        launchCancelableTask(
            "Batch Deploying...",
            onProgressDone = { token, results: List<com.alianga.idea.deploy.model.DeployResult> ->
                val successCount = results.count { it.success }
                updateLastReport("DEPLOY", results.mapNotNull { it.reportGroup })
                progressBar.value = 100
                progressLabel.text = if (token.isCancelled())
                    DeployXBundle.message("toolwindow.progress.aborted")
                else
                    DeployXBundle.message("toolwindow.progress.batchDeployComplete", successCount, results.size)
                refreshHistory()
                if (!token.isCancelled()) notifyTransferResult(successCount, results.size)
            },
            taskBody = { cancelToken ->
                deployService.deployBatch(
                    items,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 公开方法：供右键菜单 Action 调用 files-from 批量预览。
     */
    fun executePreviewBatch(items: List<UploadItem>) {
        if (items.isEmpty()) {
            appendLog(DeployXBundle.message("toolwindow.log.noPreviewItems"))
            return
        }
        // 1.0.8：操作执行前自动切到日志页
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.batchPreviewStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.batchPreviewing")

        launchCancelableTask(
            "Batch Previewing...",
            onProgressDone = { token, results: List<com.alianga.idea.deploy.model.SyncResult> ->
                val successCount = results.count { it.success }
                progressBar.value = 100
                progressLabel.text = if (token.isCancelled())
                    DeployXBundle.message("toolwindow.progress.aborted")
                else
                    DeployXBundle.message("toolwindow.progress.batchPreviewComplete", successCount, results.size)
            },
            taskBody = { cancelToken ->
                deployService.uploadBatch(
                    items,
                    dryRun = true,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 公开方法：供右键菜单 Action 调用 files-from 批量下载（拉取）。
     *
     * 1.0.8 调整：自动切到日志页；并改名为「拉取」语义，操作面板"拉取"按钮也走这里。
     */
    fun executeDownloadBatch(items: List<DownloadItem>) = executePullBatch(items)

    /**
     * 1.0.8 新增：批量拉取（从服务器下载到本地）入口。
     * 流程与 [executeDeployBatch] 对称：自动切到日志页、设置进度条、调 [DeployService.downloadBatch]，
     * 完成后刷新历史、发系统通知。
     */
    fun executePullBatch(items: List<DownloadItem>) {
        if (items.isEmpty()) {
            appendLog(DeployXBundle.message("toolwindow.log.noFilesToDownload"))
            return
        }
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.batchPullStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.pulling")

        launchCancelableTask(
            "Batch Pulling...",
            onProgressDone = { token, results: List<com.alianga.idea.deploy.model.SyncResult> ->
                val successCount = results.count { it.success }
                progressBar.value = 100
                progressLabel.text = if (token.isCancelled())
                    DeployXBundle.message("toolwindow.progress.aborted")
                else
                    DeployXBundle.message("toolwindow.progress.pullComplete")
                refreshHistory()
                if (!token.isCancelled()) notifyTransferResult(successCount, results.size)
            },
            taskBody = { cancelToken ->
                deployService.downloadBatch(
                    items,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 1.0.8 新增：批量预览拉取（dry-run）。复用 [DeployService.downloadBatch] 的 dryRun 路径，
     * 不写入历史；并生成一份 [UpdateReport]（operationType=PULL）写入 [lastUpdateReportText]，
     * 使顶部"复制报告"按钮可复制预览结果。
     */
    fun executePreviewPullBatch(items: List<DownloadItem>) {
        if (items.isEmpty()) {
            appendLog(DeployXBundle.message("toolwindow.log.noFilesToDownload"))
            return
        }
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.batchPreviewPullStart", items.size))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.previewPulling")

        launchCancelableTask(
            "Batch Previewing Pull...",
            onProgressDone = { token, results: List<com.alianga.idea.deploy.model.SyncResult> ->
                val successCount = results.count { it.success }
                updateLastReport("PULL", results.mapNotNull { it.reportGroup })
                progressBar.value = 100
                progressLabel.text = if (token.isCancelled())
                    DeployXBundle.message("toolwindow.progress.aborted")
                else
                    DeployXBundle.message("toolwindow.progress.previewPullComplete")
            },
            taskBody = { cancelToken ->
                deployService.downloadBatch(
                    items,
                    dryRun = true,
                    serverLogCallback = { serverId, line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage.coerceIn(0, 100)
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 公开方法：供右键菜单 Action 调用部署
     */
    fun executeDeploy(request: DeployRequest) {
        appendLog(DeployXBundle.message("toolwindow.log.startDeploy"))
        // 1.0.8：操作执行前自动切到日志页
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.local", request.localPath))
        appendLog(DeployXBundle.message("toolwindow.log.remote", request.serverId, request.remotePath))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.deploying")

        launchCancelableTask(
            "Deploying...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.DeployResult ->
                progressBar.value = if (result.success) 100 else progressBar.value
                updateLastReport("DEPLOY", listOfNotNull(result.reportGroup))
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.deployComplete")
                    else -> DeployXBundle.message("toolwindow.progress.deployFailedWithError", result.error ?: "")
                }
                refreshHistory()
            },
            taskBody = { cancelToken ->
                deployService.deploy(
                    request,
                    logCallback = { line -> appendLog(request.serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 公开方法：供右键菜单 Action 调用快速推送
     */
    fun executePush(localPath: String, serverId: String?) {
        appendLog(DeployXBundle.message("toolwindow.log.quickPush"))
        // 1.0.8：操作执行前自动切到日志页
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.local", localPath))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.pushing")

        launchCancelableTask(
            "Quick Push...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.DeployResult ->
                progressBar.value = if (result.success) 100 else progressBar.value
                updateLastReport("QUICK_PUSH", listOfNotNull(result.reportGroup))
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.pushComplete")
                    else -> DeployXBundle.message("toolwindow.progress.pushFailedWithError", result.error ?: "")
                }
                refreshHistory()
            },
            taskBody = { cancelToken ->
                deployService.push(
                    localPath,
                    serverId,
                    logCallback = { line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 公开方法：供右键菜单 Action 调用预览
     */
    fun executePreview(localPath: String, remotePath: String, serverId: String) {
        appendLog(DeployXBundle.message("toolwindow.log.previewSync"))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.previewing")

        launchCancelableTask(
            "Previewing Sync...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.SyncResult ->
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.previewComplete")
                    else -> DeployXBundle.message("toolwindow.progress.previewFailed")
                }
                if (!token.isCancelled() && !result.success) appendLog("[ERROR] ${result.error}")
            },
            taskBody = { cancelToken ->
                SyncService.getInstance().previewSync(localPath, remotePath, serverId) { line ->
                    appendLog(serverId, line)
                }
            }
        )
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
        applyLogFont(area)
        area.lineWrap = true
        area.wrapStyleWord = true
    }

    /** 按设置中的字体大小刷新给定日志区的字体。 */
    private fun applyLogFont(area: JBTextArea) {
        val size = FileSyncSettings.getInstance().logFontSize
        area.font = Font("Monospaced", Font.PLAIN, size)
    }

    /** 重新应用日志字体大小到所有已创建的日志区（主日志 + 各服务器子日志）。 */
    fun reapplyLogFont() {
        applyLogFont(logArea)
        serverLogAreas.values.forEach { applyLogFont(it) }
    }

    private fun getOrCreateServerLogArea(serverId: String): JBTextArea {
        return serverLogAreas.getOrPut(serverId) {
            JBTextArea().also { area ->
                configureLogArea(area)
                val server = serverManager.getServer(serverId)
                val title = if (server != null && server.name != server.id) "${server.id} - ${server.name}" else serverId
                // 1.0.8：每个服务器分组 Tab 内部顶部加「复制日志」按钮，修复了"切换到不同服务器
                // 分组后点击复制还是复制最后一个完成部署的日志"的问题——每个 Tab 持有各自的
                // area 引用，按钮直接读自己 area 的文本。
                logTabbedPane.addTab(title, AllIcons.Nodes.LogFolder, buildLogTabContent(area, serverId))
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
        }
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    /**
     * 1.0.8 新增：切换到日志页 Tab（"日志" 在 tabbedPane 中的 index = 1）。
     * 1.0.6 曾禁用自动跳转以避免操作页进度条/终止按钮不可见；本次把终止按钮
     * 迁移到日志页顶部后，恢复自动跳转（用户点预览/部署/拉取/快速推送/预览拉取
     * 后立即看到实时日志和终止按钮）。
     */
    fun switchToLogTab() {
        if (tabbedPane.selectedIndex != 1) {
            tabbedPane.selectedIndex = 1
        }
    }

    private fun previewSync() {
        val localPath = localPathField.text.trim()
        val remotePath = remotePathField.text.trim()
        val serverId = getSelectedServerId()

        if (localPath.isEmpty() || remotePath.isEmpty() || serverId == null) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.fillSyncInfo"), "Preview Sync")
            return
        }

        // 1.0.8：操作执行前自动切到日志页（终止按钮已迁至日志页顶部）
        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.previewSync"))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.previewing")

        launchCancelableTask(
            "Previewing Sync...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.SyncResult ->
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.previewComplete")
                    else -> DeployXBundle.message("toolwindow.progress.previewFailed")
                }
                if (!token.isCancelled() && !result.success) appendLog("[ERROR] ${result.error}")
            },
            taskBody = { cancelToken ->
                // previewSync 内部走 TransferService.transfer（dry-run），TransferService 已支持 cancelToken
                // 但 SyncService.previewSync 签名未暴露 cancelToken；预览通常很快，这里不强制传递
                SyncService.getInstance().previewSync(localPath, remotePath, serverId) { line ->
                    appendLog(serverId, line)
                }
            }
        )
    }

    /**
     * 1.0.8 新增：单文件/目录的预览拉取（dry-run）。
     * 复用 [SyncService.previewPull]，不写入历史、不执行实际下载。
     */
    private fun previewPull() {
        val localPath = localPathField.text.trim()
        val remotePath = remotePathField.text.trim()
        val serverId = getSelectedServerId()

        if (localPath.isEmpty() || remotePath.isEmpty() || serverId == null) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.fillSyncInfo"), "Preview Pull")
            return
        }

        switchToLogTab()
        appendLog(DeployXBundle.message("toolwindow.log.startPreviewPull"))
        appendLog(DeployXBundle.message("toolwindow.log.local", localPath))
        appendLog(DeployXBundle.message("toolwindow.log.remote", serverId, remotePath))
        progressBar.value = 0
        progressLabel.text = DeployXBundle.message("toolwindow.progress.previewPulling")

        launchCancelableTask(
            "Previewing Pull...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.SyncResult ->
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.previewPullComplete")
                    else -> DeployXBundle.message("toolwindow.progress.previewPullFailed")
                }
                if (!token.isCancelled() && !result.success) appendLog("[ERROR] ${result.error}")
            },
            taskBody = { _ ->
                SyncService.getInstance().previewPull(remotePath, localPath, serverId) { line ->
                    appendLog(serverId, line)
                }
            }
        )
    }

    /**
     * 1.0.8 新增：单文件/目录的拉取（从服务器下载到本地）。
     * 通过构造一个 [DownloadItem] 并复用 [executePullBatch] 执行，与右键 Pull 一致。
     */
    private fun pullFromServer() {
        val localPath = localPathField.text.trim()
        val remotePath = remotePathField.text.trim()
        val serverId = getSelectedServerId()

        if (localPath.isEmpty() || remotePath.isEmpty() || serverId == null) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.fillSyncInfo"), "Pull")
            return
        }

        // 尝试用映射解析 localBaseDir / remoteBaseDir，失败时退化用表单值
        val isDirectory = java.io.File(localPath).isDirectory
        val resolved = MappingManager.getInstance().resolveMappingByLocalPath(localPath, isDirectory)
        val (localBaseDir, remoteBaseDir, relativePath) = if (resolved != null) {
            Triple(resolved.mapping.localDir, resolved.resolvedRemoteDir, resolved.relativePath)
        } else {
            // 无映射时以 localPath/remotePath 自身为基础（relativePath = ""）
            Triple(localPath, remotePath, "")
        }

        val item = DownloadItem(
            localPath = localPath,
            isDirectory = isDirectory,
            serverId = serverId,
            mappingId = resolved?.mapping?.effectiveId.orEmpty(),
            localBaseDir = localBaseDir,
            remoteBaseDir = remoteBaseDir,
            relativePath = relativePath
        )
        executePullBatch(listOf(item))
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

        launchCancelableTask(
            "Deploying...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.DeployResult ->
                progressBar.value = if (result.success) 100 else progressBar.value
                updateLastReport("MANUAL_DEPLOY", listOfNotNull(result.reportGroup))
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.deployComplete")
                    else -> DeployXBundle.message("toolwindow.progress.deployFailed")
                }
                refreshHistory()
            },
            taskBody = { cancelToken ->
                deployService.deploy(
                    request,
                    logCallback = { line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
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

        launchCancelableTask(
            "Quick Push...",
            onProgressDone = { token, result: com.alianga.idea.deploy.model.DeployResult ->
                progressBar.value = if (result.success) 100 else progressBar.value
                updateLastReport("QUICK_PUSH", listOfNotNull(result.reportGroup))
                progressLabel.text = when {
                    token.isCancelled() -> DeployXBundle.message("toolwindow.progress.aborted")
                    result.success -> DeployXBundle.message("toolwindow.progress.pushComplete")
                    else -> DeployXBundle.message("toolwindow.progress.pushFailed")
                }
                refreshHistory()
            },
            taskBody = { cancelToken ->
                deployService.push(
                    localPath,
                    serverId,
                    logCallback = { line -> appendLog(serverId, line) },
                    progressCallback = { progress ->
                        SwingUtilities.invokeLater {
                            progressBar.value = progress.percentage
                            progressLabel.text = "${progress.currentFile} ${progress.percentage}% ${progress.speed}"
                        }
                    },
                    cancelToken = cancelToken
                )
            }
        )
    }

    /**
     * 打开远程文件浏览器，浏览当前选中服务器的文件结构
     */
    private fun browseRemote() {
        val serverId = getSelectedServerId()
        if (serverId == null) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.validation.selectServerFirst"), DeployXBundle.message("toolwindow.log.browseRemoteTitle"))
            return
        }
        val server = serverManager.getServer(serverId)
        if (server == null) {
            Messages.showErrorDialog(DeployXBundle.message("toolwindow.validation.serverNotFound"), DeployXBundle.message("toolwindow.log.browseRemoteTitle"))
            return
        }
        // LOCAL 服务器不支持远程文件浏览
        if (server.isLocal) {
            Messages.showInfoMessage(
                DeployXBundle.message("toolwindow.log.localServerNotSupported"),
                DeployXBundle.message("toolwindow.log.browseRemoteTitle")
            )
            return
        }
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(RemoteFileBrowserToolWindowFactory.TOOL_WINDOW_ID)?.show()
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
        // LOCAL 服务器不支持 SSH 终端
        if (server.isLocal) {
            Messages.showInfoMessage(
                DeployXBundle.message("toolwindow.log.localServerNotSupported"),
                DeployXBundle.message("toolwindow.log.openTerminalTitle")
            )
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

        val dialog = MappingEditDialog(null, prefillData = prefill, project = project)
        if (dialog.showAndGet()) {
            MappingManager.getInstance().addMapping(dialog.getMappingConfig())
            appendLog(DeployXBundle.message("toolwindow.log.mappedSaved", dialog.getMappingConfig().name))
            Messages.showInfoMessage(DeployXBundle.message("toolwindow.validation.mappingSaved"), DeployXBundle.message("toolwindow.validation.saveSuccess"))
        }
    }
}
