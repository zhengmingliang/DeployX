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
import com.alianga.idea.deploy.ui.AdaptiveRowPanel
import com.alianga.idea.deploy.ui.UiButtonFactory
import com.alianga.idea.deploy.ui.WrapLayout
import com.alianga.idea.deploy.ui.DeployMode
import com.alianga.idea.deploy.ui.MappingAutoFill
import com.alianga.idea.deploy.ui.DeployXColors
import com.alianga.idea.deploy.ui.OperationSummary
import com.alianga.idea.deploy.ui.ServerDisplay
import com.alianga.idea.deploy.ui.StatusArea
import com.alianga.idea.deploy.ui.dialog.HistoryDetailDialog
import com.alianga.idea.deploy.ui.dialog.MappingSelectionDialog
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
import com.intellij.ui.components.JBOptionButton
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
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.AbstractAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ide.DataManager
import javax.swing.Action
import javax.swing.*

/**
 * 文件同步工具窗口主面板
 * 通过 companion object 暴露实例引用，供右键菜单 Action 调用
 */
class FileSyncToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {

    /** 1.1.0：随内容页签释放，注销语言 / 服务器监听，避免泄漏 */
    override fun dispose() {
        languageChangeUnsubscribe()
        serverListUnsubscribe()
        panelByProject.remove(project.hashCode().toString())
    }


    companion object {
        /** 按 Project 存储面板实例，供 Action 调用 */
        private val panelByProject = linkedMapOf<String, FileSyncToolWindowPanel>()

        /**
         * “清除日志”图标。使用平台 AllIcons.Actions.GC（垃圾桶），
         * 语义贴合且颜色随主题自适应，与工具栏其他 AllIcons 图标风格统一。
         */
        private val CLEAR_LOG_ICON: Icon = AllIcons.Actions.GC

        private const val KEY_PUSH_MODE = "deployx.toolwindow.pushMode"
        private const val KEY_PULL_MODE = "deployx.toolwindow.pullMode"
        /** 服务器下拉末尾的「管理服务器…」占位项（不是服务器 id） */
        private const val MANAGE_SERVERS_ITEM = "\u0000deployx.manageServers"

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
    // 第 6 轮：平台的 ComboBoxWithWidePopup——弹出列表宽度按最长项展开（下拉框本身宽度不变），服务器名 / 主机不再被截断
    private val serverCombo = com.intellij.openapi.ui.ComboBoxWithWidePopup(javax.swing.DefaultComboBoxModel<String>())
    private var lastServerIndex = -1
    // 1.0.8：本地路径改为 TextFieldWithBrowseButton，支持选目录（与选文件并存）
    // 1.0.8：传入 JBTextField 以便使用 emptyText 占位提示
    // 1.1.0：改为内置浏览按钮（Built-in button，Shift+Enter），本地用文件夹图标、远程用服务器图标
    private val localPathField = com.alianga.idea.deploy.ui.PathField(AllIcons.General.OpenDisk, DeployXBundle.message("toolwindow.button.browseLocal"))
    private val remotePathField = com.alianga.idea.deploy.ui.PathField(AllIcons.Nodes.Deploy, DeployXBundle.message("toolwindow.tooltip.remotePathBrowse"))
    private val backupCheck = JBCheckBox(DeployXBundle.message("toolwindow.checkbox.backupBeforeDeploy"))
    private val backupDirField = JBTextField()
    private val unzipCheck = JBCheckBox(DeployXBundle.message("toolwindow.checkbox.unzipAfterUpload"))
    private val unzipDestField = JBTextField()
    private val preCommandField = CommandFieldWithScriptButton(
        project = project,
        contextProvider = { buildScriptRunContext() },
        multiline = true,
        // 1.1.1：只约束高度（2 行），宽度跟随容器，不再用未缩放的 600 抬高整页首选宽度
        preferredScrollSize = JBUI.size(80, 48),
        // 1.1.0：命令框去掉行号 gutter（单行时是噪音），「+」改为下方「插入脚本…」链接
        showLineNumbers = false,
        scriptLinkStyle = true
    )
    private val postCommandField = CommandFieldWithScriptButton(
        project = project,
        contextProvider = { buildScriptRunContext() },
        multiline = true,
        // 1.1.1：只约束高度（2 行），宽度跟随容器，不再用未缩放的 600 抬高整页首选宽度
        preferredScrollSize = JBUI.size(80, 48),
        // 1.1.0：命令框去掉行号 gutter（单行时是噪音），「+」改为下方「插入脚本…」链接
        showLineNumbers = false,
        scriptLinkStyle = true
    )

    // ===== 1.1.1：操作页按原型补齐 =====
    /** 目录映射下拉（原型「源与目标 › 目录映射」），取代手动流程里的 MappingSelectionDialog 弹框 */
    private val mappingCombo = com.intellij.openapi.ui.ComboBox<MappingManager.ResolvedMapping>()
    private val mappingLabel = JBLabel(DeployXBundle.message("toolwindow.label.mapping"))
    private val mappingHint = JBLabel().apply { font = JBUI.Fonts.smallFont(); foreground = DeployXColors.secondary; setAllowAutoWrapping(true) }
    /** 「已按映射「xxx」填充 · 重置」 */
    private val filledLabel = JBLabel().apply { font = JBUI.Fonts.smallFont(); foreground = DeployXColors.secondary }
    private val resetLink = com.intellij.ui.components.ActionLink(DeployXBundle.message("toolwindow.mapping.reset")) { reapplyCurrentMapping() }
    private val filledRow = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), 0)).apply { isOpaque = false; add(filledLabel); add(resetLink); isVisible = false }
    /** 上传前 / 后命令改为复选框开关，勾选后才展开命令框 */
    private val preCheck = JBCheckBox(DeployXBundle.message("toolwindow.label.preUploadCommand"))
    private val postCheck = JBCheckBox(DeployXBundle.message("toolwindow.label.postUploadCommand"))
    private val localErrorLabel = JBLabel(DeployXBundle.message("toolwindow.validation.localEmpty")).apply {
        font = JBUI.Fonts.smallFont(); foreground = DeployXColors.error; setAllowAutoWrapping(true); isVisible = false
    }
    /** 最近一次自动带入所用的映射（effectiveId），null = 未按映射填充 */
    private var appliedMappingId: String? = null
    /** 程序化写入本地路径时为 true，避免把自动带入误判为用户手改 */
    private var settingLocalProgrammatically = false
    /** 用户是否手动改过本地路径（改过则不再被编辑器 / 项目视图选中项覆盖） */
    private var localEditedByUser = false
    private var suppressMappingEvents = false

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
    /**
     * 1.0.8 重构：「预览 ▾」split 按钮。默认点 = 预览同步；下拉箭头弹出"预览同步 / 预览拉取"两项。
     * 文本走 AbstractAction.NAME，relocalize() 通过 putValue 刷新。
     */
    private val previewDefaultAction = object : AbstractAction(
        DeployXBundle.message("toolwindow.button.preview"), AllIcons.Actions.Preview
    ) { override fun actionPerformed(e: ActionEvent) = previewSync() }
    private val previewSyncOption = object : AbstractAction(
        DeployXBundle.message("toolwindow.button.previewSync.option"), AllIcons.Actions.Preview
    ) { override fun actionPerformed(e: ActionEvent) = previewSync() }
    private val previewPullOption = object : AbstractAction(
        DeployXBundle.message("toolwindow.button.previewPull.option"), AllIcons.Actions.Preview
    ) { override fun actionPerformed(e: ActionEvent) = previewPull() }
    private val previewButton = JBOptionButton(
        previewDefaultAction, arrayOf(previewSyncOption, previewPullOption)
    ).apply {
        toolTipText = DeployXBundle.message("toolwindow.button.preview.tooltip")
        optionTooltipText = DeployXBundle.message("toolwindow.button.preview.optionTooltip")
        addSeparator = true
    }
    /**
     * 1.0.8 重构：主按钮「部署」。用 JBOptionButton(action, emptyArray()) 拿到 IDE 默认按钮
     * accent 填充样式，区别于次要按钮（拉取/快速推送）和 split 按钮（预览）。
     */
    private val deployDefaultAction = object : AbstractAction(
        DeployXBundle.message("toolwindow.button.startDeploy"), AllIcons.Actions.Execute
    ) { override fun actionPerformed(e: ActionEvent) = startDeploy() }
    private val startDeployButton = JBOptionButton(deployDefaultAction, emptyArray()).apply {
        toolTipText = DeployXBundle.message("toolwindow.button.startDeploy.tooltip")
    }
    // 拉取（次要动作，保留普通 JButton 样式）
    private val pullButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.pull"), AllIcons.Actions.Download) { pullFromServer() }.apply {
        toolTipText = DeployXBundle.message("toolwindow.button.pull.tooltip")
    }
    private val quickPushButton = UiButtonFactory.createActionButton(DeployXBundle.message("toolwindow.button.quickPush"), AllIcons.Actions.Upload) { quickPush() }.apply {
        toolTipText = DeployXBundle.message("toolwindow.button.quickPush.tooltip")
    }
    /**
     * 1.0.9 重构：保存为映射改为小图标按钮，放到服务器行最右（与 browseRemote/openTerminal 并列），
     * 不再占用底部动作区。tooltip 文案由 relocalize() 刷新。
     */
    private val saveAsMappingButton = UiButtonFactory.createIconButton(
        DeployXBundle.message("toolwindow.button.saveAsMapping.tooltip"),
        AllIcons.Actions.MenuSaveall
    ) { saveAsMapping() }
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
    private var logToolbar: com.intellij.openapi.actionSystem.ActionToolbar? = null
    /** 设置动作，由 Factory 放进工具窗口标题栏 gear 菜单 */
    var settingsAction: AnAction? = null
        private set

    /** 1.1.0：四个页签作为 ToolWindow 内容页签（ContentManager）挂载，这里只保存页面组件 */
    private val pages = mutableListOf<JComponent>()
    /** 1.1.1：自建页签行 */
    private val tabBar = com.alianga.idea.deploy.ui.PageTabBar { onPageSelected(it) }
    /** 由 Factory 注入：切换到第 i 个内容页签 */
    var tabSelector: ((Int) -> Unit)? = null
    /** 由 Factory 注入：语言切换时刷新页签标题 */
    var tabTitleUpdater: (() -> Unit)? = null
    private var selectedPage = 0

    // 进度
    private val progressBar = JProgressBar(0, 100)
    // 1.1.1：进度文本含长路径与速度，用可截断标签避免撑宽整页（完整文本在 tooltip）
    private val progressLabel: JBLabel = StatusArea.TruncLabel().apply { text = DeployXBundle.message("toolwindow.progress.ready") }

    // 日志 tab
    private val logArea = JBTextArea()
    private val logTabbedPane = JBTabbedPane()
    private val serverLogAreas = linkedMapOf<String, JBTextArea>()

    // 历史 tab
    /** 1.1.0：历史搜索框与过滤胶囊 */
    private val historySearchField = com.intellij.ui.SearchTextField(false)
    private var historyFilterKind = com.alianga.idea.deploy.ui.HistoryFilter.Kind.ALL
    private val historyChips = linkedMapOf<com.alianga.idea.deploy.ui.HistoryFilter.Kind, JToggleButton>()
    private val historyListModel = DefaultListModel<String>()
    // 1.1.1：行宽跟随视口，长路径（如回滚备份包）截断而不是撑出横向滚动、挤掉右侧状态 / 时间
    private val historyList = object : JBList<String>(historyListModel) {
        override fun getScrollableTracksViewportWidth() = true
    }
    private var historyRecords = listOf<HistoryRecord>()
    private val scriptTabPanel = ScriptTabPanel(project)

    // 历史按钮：使用 AnAction + ActionToolbar（与顶部工具栏同一机制），
    // 间距由平台统一控制，紧凑且一致；按钮以图标显示，文案与说明通过 tooltip/description 呈现。
    // 回滚按钮图标与历史列表中“可回滚”记录的图标一致（均为 AllIcons.Actions.Rollback）。
    private val historyRefreshAction = UiButtonFactory.createLocalizedAction("toolwindow.history.refresh", AllIcons.Actions.Refresh, "toolwindow.history.refresh.tooltip") { refreshHistory() }
    private val historyFillConfigAction = UiButtonFactory.createLocalizedAction("toolwindow.history.fillConfig", AllIcons.Actions.Edit, "toolwindow.history.fillConfig.tooltip") { fillFromHistory() }
    private val historyViewDetailAction = UiButtonFactory.createLocalizedAction("toolwindow.history.viewDetail", AllIcons.Actions.Preview, "toolwindow.history.viewDetail.tooltip") { viewHistoryDetail() }
    private val historyClearAction = UiButtonFactory.createLocalizedAction("toolwindow.history.clear", AllIcons.Actions.GC, "toolwindow.history.clear.tooltip") { clearHistory() }
    /** 历史工具栏（与顶部工具栏同机制，间距由平台统一控制） */
    private var historyToolbar: ActionToolbar? = null

    /** 历史列表为空时的占位提示（替代原先的空白，明确告知用户“暂无记录”） */
    private val historyEmptyLabel = JBLabel(DeployXBundle.message("toolwindow.history.empty"), SwingConstants.CENTER)
    /** 历史面板的卡片布局：有记录显示列表，无记录显示占位提示 */
    private val historyCardLayout = CardLayout()
    private lateinit var historyCardPanel: JPanel


    // 1.0.9 重构：操作面板的 3 个 TitledBorder 分组 panel 引用（保留以便 relocalize 时刷新标题）
    private lateinit var serverPathPanel: JPanel
    private lateinit var deployOptionsPanel: JPanel
    private lateinit var hooksPanel: JPanel
    private lateinit var serverPathGroup: com.alianga.idea.deploy.ui.GroupHeader
    private lateinit var deployFlowGroup: com.alianga.idea.deploy.ui.GroupHeader
    // 部署选项下两个缩进的子 panel（备份目录/解压目录）
    private lateinit var backupIndentedPanel: JPanel
    private lateinit var unzipIndentedPanel: JPanel
    private lateinit var preBlock: JPanel
    private lateinit var postBlock: JPanel

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

    // 1.1.0：以下成员须在 init 之前声明（Kotlin 按文本顺序初始化）
    private var taskStartedAt = 0L
    /** 批量传输已经由 notifyTransferResult 发过通知时，结束时不再重复通知 */
    private var transferNotified = false
    private var pushMode = DeployMode.DEPLOY
    private var pullMode = DeployMode.PULL
    private val deploySplitButton = JBOptionButton(null, null).apply { addSeparator = true }
    private val pullSplitButton = JBOptionButton(null, null).apply { addSeparator = true }
    private val statusArea: StatusArea by lazy {
        StatusArea(
            progressBar, progressLabel,
            DeployXBundle.message("toolwindow.button.abort"), { abortCurrentTask() },
            DeployXBundle.message("toolwindow.status.viewLog"), { switchToLogTab() },
            DeployXBundle.message("toolwindow.status.copyReport"), { copyLastReport() },
            DeployXBundle.message("toolwindow.status.close")
        )
    }

    // 1.1.1 修复：历史详情区字段必须在 init 之前声明（setupUI 在 init 中调用，之后声明的属性此时仍为 null）
    private var historyRecordsBefore = listOf<HistoryRecord>()

    // ===== 1.1.1 历史详情区 =====
    private val hdHeader = JBLabel()
    private val hdRows = JBLabel()
    private val hdSteps = JBLabel()
    private val hdFiles = com.intellij.ui.components.JBTextArea().apply { isEditable = false; rows = 5 }
    private val hdRollbackReason = JBLabel().apply { foreground = DeployXColors.warning }
    private val hdRollbackBtn = JButton(DeployXBundle.message("toolwindow.history.rollback"), AllIcons.Actions.Rollback)
    private val hdRerunBtn = JButton(DeployXBundle.message("history.detail.rerun.deploy"), AllIcons.Actions.Execute)
    private val hdCopyBtn = JButton(DeployXBundle.message("toolwindow.history.copyReport"))
    private val hdExportBtn = JButton(DeployXBundle.message("toolwindow.history.exportReport"))

    init {
        // 1.1.1（review #16）：下拉末尾「管理服务器…」项（原型），选中后打开设置并恢复原选中项
        val serverRenderer = com.alianga.idea.deploy.ui.ServerComboRenderer { _, value ->
            value?.toString()?.let { serverManager.getServer(it) }
        }
        serverCombo.renderer = javax.swing.ListCellRenderer<String> { list, value, index, sel, focus ->
            if (value == MANAGE_SERVERS_ITEM) {
                val label = JBLabel(DeployXBundle.message("toolwindow.server.manage"), AllIcons.General.Settings, SwingConstants.LEADING).apply {
                    isOpaque = true
                    background = if (sel && index >= 0) list.selectionBackground else list.background
                    foreground = if (sel && index >= 0) list.selectionForeground else DeployXColors.link
                    border = JBUI.Borders.empty(3, 6)
                }
                if (index < 0) label
                else JPanel(BorderLayout()).apply {
                    // 第 6 轮：平台 combo UI 会覆盖渲染组件的 border，上一版用 border 画的分隔线被吞掉；
                    // 改为独立的 1px 子组件，确保分隔线真正画出来
                    isOpaque = true
                    background = list.background
                    add(JPanel().apply {
                        isOpaque = false
                        border = JBUI.Borders.empty(3, 0)
                        layout = BorderLayout()
                        add(object : JPanel() {
                            init { isOpaque = true; background = com.intellij.ui.JBColor(java.awt.Color(0xC9CCD6), java.awt.Color(0x5A5D63)) }
                            override fun getPreferredSize() = java.awt.Dimension(1, JBUI.scale(1).coerceAtLeast(1))
                        }, BorderLayout.CENTER)
                    }, BorderLayout.NORTH)
                    add(label, BorderLayout.CENTER)
                }
            } else serverRenderer.getListCellRendererComponent(list, value, index, sel, focus)
        }
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
        // 1.1.0：不再有全局 5 图标工具栏（现状问题 #1）。设置进标题栏 gear 菜单（见 Factory），
        // 其余动作按页签归属：操作页=刷新；日志页=复制报告/导出报告/清空。
        this.settingsAction = settingsAction
        val opsToolbar = ActionManager.getInstance().createActionToolbar("DeployX.Operation", DefaultActionGroup(refreshAction), true)
        val logToolbar = ActionManager.getInstance().createActionToolbar(
            "DeployX.Log", DefaultActionGroup(copyReportAction, exportReportAction, Separator.getInstance(), clearLogAction), true
        )
        this.toolbar = opsToolbar
        this.logToolbar = logToolbar

        // ===== 操作面板 =====
        // 1.0.9 重构：3 个 TitledBorder 分组（服务器与路径 / 部署选项 / 执行命令），
        // 按钮区分主次层级，「保存为映射」改为小图标放到服务器行最右。
        val serverButtonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(browseRemoteButton)
            add(Box.createHorizontalStrut(4))
            add(openTerminalButton)
            add(Box.createHorizontalStrut(4))
            // 1.0.9：保存为映射改为小图标，放服务器行最右，不再占用底部动作区
            add(saveAsMappingButton)
        }
        // 服务器行：宽度足够时按钮组停靠下拉框右侧；侧边栏拉窄时自动换到下拉框下方，
        // 避免右侧按钮被裁剪不可见
        // 1.1.1：窄档下三个图标按钮仍留在下拉框右侧（minMainWidth 160 → 100）
        val serverWithTerminalPanel = AdaptiveRowPanel(
            main = serverCombo,
            side = serverButtonsPanel,
            minMainWidth = JBUI.scale(100)
        )

        // 分组 1：服务器与路径
        // FormBuilder 两列 GridBag：标签列 weightx=0 不换行，窄宽时把字段列挤到不可用。
        // 改为单列 + AdaptiveRowPanel.labeled，极窄时标签落到字段上方，字段仍满宽。
        // 1.1.1：字段顺序按原型——本地文件 → 目标服务器 → 目录映射 → 远程路径
        val localWithError = JPanel(BorderLayout()).apply { isOpaque = false; add(localPathField, BorderLayout.CENTER); add(localErrorLabel, BorderLayout.SOUTH) }
        val serverWithManage = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(serverWithTerminalPanel, BorderLayout.CENTER)
        }
        val mappingWithHint = JPanel(BorderLayout()).apply { isOpaque = false; add(mappingCombo, BorderLayout.CENTER); add(mappingHint, BorderLayout.SOUTH) }
        serverPathPanel = FormBuilder.createFormBuilder()
            .addComponent(AdaptiveRowPanel.labeled(localFileLabel, localWithError))
            .addComponent(AdaptiveRowPanel.labeled(targetServerLabel, serverWithManage, JBUI.scale(100)))
            .addComponent(AdaptiveRowPanel.labeled(mappingLabel, mappingWithHint))
            .addComponent(AdaptiveRowPanel.labeled(remotePathLabel, remotePathField))
            .panel
        alignFormLabels()
        serverPathPanel.putClientProperty(AdaptiveRowPanel.FORM_ROOT, true)
        serverPathGroup = com.alianga.idea.deploy.ui.GroupHeader(DeployXBundle.message("toolwindow.group.serverAndPaths"), serverPathPanel)

        // 分组 2：部署选项（备份/解压，未勾选时缩进子字段灰显）
        // 缩进由子 Panel 的 EmptyBorder left=12 实现
        backupIndentedPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyLeft(12)
            val inner = FormBuilder.createFormBuilder()
                .addComponent(AdaptiveRowPanel.labeled(backupDirLabel, backupDirField))
                .panel
            add(inner, BorderLayout.CENTER)
        }
        unzipIndentedPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyLeft(12)
            val inner = FormBuilder.createFormBuilder()
                .addComponent(AdaptiveRowPanel.labeled(unzipDirLabel, unzipDestField))
                .panel
            add(inner, BorderLayout.CENTER)
        }
        deployOptionsPanel = FormBuilder.createFormBuilder()
            .addComponent(backupCheck)
            .addComponent(backupIndentedPanel)
            .addComponent(unzipCheck)
            .addComponent(unzipIndentedPanel)
            .panel
        deployOptionsPanel.putClientProperty(AdaptiveRowPanel.FORM_ROOT, true)

        // 分组 3：执行命令
        // 1.1.1：命令改为复选框 + 勾选后展开的 2 行命令框（缩进），下方注释「在远程目录执行 · 每行一条」
        fun commandBlock(field: JComponent): JPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(22)
            add(field, BorderLayout.CENTER)
            add(JBLabel(DeployXBundle.message("toolwindow.command.comment")).apply {
                font = JBUI.Fonts.smallFont(); foreground = DeployXColors.secondary
            }, BorderLayout.SOUTH)
        }
        preBlock = commandBlock(preCommandField)
        postBlock = commandBlock(postCommandField)
        hooksPanel = FormBuilder.createFormBuilder()
            .addComponent(preCheck)
            .addComponent(preBlock)
            .addComponent(postCheck)
            .addComponent(postBlock)
            .panel
        // 1.1.0：「部署选项」「执行命令」合并为一个「部署流程」分组（标题 + 分隔线）
        val flowContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            deployOptionsPanel.alignmentX = Component.LEFT_ALIGNMENT
            hooksPanel.alignmentX = Component.LEFT_ALIGNMENT
            filledRow.alignmentX = Component.LEFT_ALIGNMENT
            add(filledRow)
            add(deployOptionsPanel)
            add(hooksPanel)
        }
        deployFlowGroup = com.alianga.idea.deploy.ui.GroupHeader(DeployXBundle.message("toolwindow.group.deployOptions"), flowContent)

        // 1.1.0：底部操作栏只有两组——左「拉取 ▾」，右主按钮「部署 ▾」（现状问题 #8）
        applyDeployMode(DeployMode.parse(com.intellij.ide.util.PropertiesComponent.getInstance().getValue(KEY_PUSH_MODE), DeployMode.DEPLOY))
        // 1.1.1：拉取主体固定为「拉取」（原型），预览拉取只在菜单里，不再被记住
        applyPullMode(DeployMode.PULL)
        // 1.1.1：窄档自动上下堆叠（主按钮在上），不再重叠 / 裁切
        val actionBar = object : JPanel() {
            // 宽度跟随父容器而不是撑开页面；高度随堆叠状态变化
            override fun getPreferredSize(): Dimension =
                Dimension(layout.minimumLayoutSize(this).width, layout.preferredLayoutSize(this).height)
        }.apply {
            border = JBUI.Borders.emptyTop(8)
            layout = com.alianga.idea.deploy.ui.ActionBarLayout(pullSplitButton, deploySplitButton, JBUI.scale(8))
            add(pullSplitButton)
            add(deploySplitButton)
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) { revalidate() }
            })
        }

        // 进度面板：进度条整行、状态标签在下一行。进度文本含文件路径与速度，较长，
        // 放右侧在窄面板下会被截断，换行到下方后始终完整可见。1.0.9 加 8px 顶部内边距
        // 1.1.0：进度区改为固定位置的状态区（空闲摘要 / 运行中 / 完成），见 StatusArea

        // 操作面板实现 Scrollable 且宽度跟随视口：内容整体收缩换行，而不是出现
        // 横向滚动条把右侧按钮藏在可视区外（拉窄时由各子面板自行换行/收缩兜底）
        val operationPanel = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension? = preferredSize
            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(64)
            override fun getScrollableTracksViewportWidth(): Boolean = true
            override fun getScrollableTracksViewportHeight(): Boolean = false
        }
        operationPanel.layout = BoxLayout(operationPanel, BoxLayout.Y_AXIS)
        operationPanel.border = JBUI.Borders.empty(12) // 规范：工具窗口内容内边距 12
        operationPanel.add(serverPathGroup)
        operationPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
        operationPanel.add(deployFlowGroup)
        operationPanel.add(Box.createVerticalStrut(JBUI.scale(12)))
        // 1.1.0：状态区 + 操作栏固定在底部，表单内容区单独滚动（窄档操作栏不被挤掉）
        val bottomBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(DeployXColors.border), JBUI.Borders.empty(0, 12, 10, 12))
            add(statusArea, BorderLayout.CENTER)
            add(actionBar, BorderLayout.SOUTH)
        }
        val opsPage = JPanel(BorderLayout()).apply {
            add(JBScrollPane(operationPanel).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
            minimumSize = JBUI.size(320, 200)
        }
        installSummaryListeners()

        // ===== 日志面板 =====
        configureLogArea(logArea)
        // 1.0.8：每个 Tab 内部顶部加「复制此 Tab 日志」按钮，logArea 对应"全部" Tab（serverId=null）
        logTabbedPane.addTab(DeployXBundle.message("toolwindow.tab.all"), AllIcons.Nodes.LogFolder, buildLogTabContent(logArea, null))
        // 1.0.8：日志面板顶部加工具栏（终止按钮放在最右侧），始终可见
        // 窄宽时「终止」换到复制按钮下方，避免 EAST 把复制按钮挤出可视区
        val abortHolder = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
            add(abortButton)
        }
        val logHeaderPanel = AdaptiveRowPanel(
            main = buildLogHeaderCopyBar(),
            side = abortHolder,
            minMainWidth = JBUI.scale(140)
        ).apply {
            border = JBUI.Borders.empty(4)
        }
        val logPanel = JPanel(BorderLayout()).apply {
            add(logHeaderPanel, BorderLayout.NORTH)
            add(logTabbedPane, BorderLayout.CENTER)
        }

        // ===== 历史面板 =====
        // 自定义渲染器：可回滚的记录显示回滚图标，与「回滚」按钮图标保持一致，
        // 便于用户快速识别哪些历史记录可以回滚。
        // 1.1.1：两行卡片 + 今天/昨天/更早分组头（分组头画在组内第一条记录上方，列表索引仍与记录一一对应）
        historyList.cellRenderer = ListCellRenderer<String> { list, _, index, isSelected, _ ->
            historyRowRenderer(list, index, isSelected)
        }
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.addListSelectionListener { if (!it.valueIsAdjusting) updateHistoryDetail() }

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
        // 1.1.1：页签工具栏只保留 刷新 / 清空；回滚、重新执行、复制 / 导出报告、日志、删除移到下方详情区，
        // 填充配置与详情对话框进「更多」（详情也可双击打开）——入口移动，功能不删
        val historyActionGroup = DefaultActionGroup().apply {
            add(historyRefreshAction)
            addSeparator()
            add(historyClearAction)
        }
        val historyToolbarInstance = ActionManager.getInstance().createActionToolbar("FileSyncHistoryToolbar", historyActionGroup, true)
        // 1.1.1：targetComponent 必须是实际显示的组件。外层面板自 1.1.0 起不再显示（setContent(JPanel())），
        // 指向它会让工具栏取不到数据上下文、动作全部失效；改为在 historyPanel 创建后指向它（见下方）
        historyToolbar = historyToolbarInstance
        historyToolbarInstance.targetComponent = this
        // 1.1.0：过滤栏——搜索（动作 / 服务器 / 路径）+ 胶囊过滤：全部 / 成功 / 失败 / 可回滚，各带计数
        historySearchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = refreshHistory()
        })
        val chipGroup = ButtonGroup()
        val chipBar = JPanel(WrapLayout(FlowLayout.LEADING, JBUI.scale(4), JBUI.scale(2)))
        com.alianga.idea.deploy.ui.HistoryFilter.Kind.entries.forEach { kind ->
            val chip = JToggleButton().apply {
                isSelected = kind == historyFilterKind
                putClientProperty("JButton.buttonType", "roundRect")
                addActionListener { historyFilterKind = kind; refreshHistory() }
            }
            chipGroup.add(chip); chipBar.add(chip); historyChips[kind] = chip
        }
        // 1.1.1：历史工具栏移到自建页签行右侧，这里只保留搜索与过滤
        val historyButtonPanel = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 8, 4, 8)
                add(historySearchField, BorderLayout.NORTH)
                add(chipBar, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
        historyCardPanel = JPanel(historyCardLayout).apply {
            add(com.intellij.ui.OnePixelSplitter(true, "DeployX.history.split", 0.5f).apply {
                firstComponent = JBScrollPane(historyList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
                secondComponent = buildHistoryDetailPanel()
            }, "list")
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
        // 1.1.1：自建页签行（PageTabBar）取代平台内容页签——窄宽下「操作」永不隐藏；
        // 各页工具动作放在页签行右侧，空间不足折进 ⋯。面板自身重新作为唯一可见内容，
        // 所有工具栏的 targetComponent 均为 this（实际显示中）
        opsToolbar.targetComponent = this
        logToolbar.targetComponent = this
        pages.clear()
        pages += opsPage
        pages += logPanel
        pages += historyPanel
        pages += scriptTabPanel
        val titles = pageTitles()
        tabBar.addPage(titles[0], opsPage, opsToolbar)
        tabBar.addPage(titles[1], logPanel, logToolbar)
        tabBar.addPage(titles[2], historyPanel, historyToolbarInstance)
        tabBar.addPage(titles[3], scriptTabPanel, scriptTabPanel.createTabToolbar())
        setContent(tabBar)
    }

    /** 页签标题（与 [pageComponents] 顺序一致） */
    fun pageTitles(): List<String> = listOf("toolwindow.tab.operation", "toolwindow.tab.log", "toolwindow.tab.history", "toolwindow.tab.script")
        .map { DeployXBundle.message(it) }

    fun pageComponents(): List<JComponent> = pages

    /** 页签切换回调：进入「历史」页时重新加载，确保展示最新记录 */
    fun onPageSelected(index: Int) {
        selectedPage = index
        if (index == 2) refreshHistory()
    }

    private fun selectTab(index: Int) {
        if (tabBar.selectedIndex != index) tabBar.select(index) else onPageSelected(index)
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
        preCheck.text = DeployXBundle.message("toolwindow.label.preUploadCommand")
        postCheck.text = DeployXBundle.message("toolwindow.label.postUploadCommand")
        mappingLabel.text = DeployXBundle.message("toolwindow.label.mapping")
        resetLink.text = DeployXBundle.message("toolwindow.mapping.reset")
        localErrorLabel.text = DeployXBundle.message("toolwindow.validation.localEmpty")
        alignFormLabels()
        unzipCheck.text = DeployXBundle.message("toolwindow.checkbox.unzipAfterUpload")

        // 1.0.9 重构：3 个 TitledBorder 分组标题刷新
        if (::serverPathPanel.isInitialized) {
            serverPathGroup.title = DeployXBundle.message("toolwindow.group.serverAndPaths")
            deployFlowGroup.title = DeployXBundle.message("toolwindow.group.deployOptions")
        }

        // 1.0.9 重构：JBOptionButton 文本走 AbstractAction.NAME，刷新后 repaint
        previewDefaultAction.putValue(Action.NAME, DeployXBundle.message("toolwindow.button.preview"))
        previewSyncOption.putValue(Action.NAME, DeployXBundle.message("toolwindow.button.previewSync.option"))
        previewPullOption.putValue(Action.NAME, DeployXBundle.message("toolwindow.button.previewPull.option"))
        deployDefaultAction.putValue(Action.NAME, DeployXBundle.message("toolwindow.button.startDeploy"))
        previewButton.toolTipText = DeployXBundle.message("toolwindow.button.preview.tooltip")
        previewButton.optionTooltipText = DeployXBundle.message("toolwindow.button.preview.optionTooltip")
        startDeployButton.toolTipText = DeployXBundle.message("toolwindow.button.startDeploy.tooltip")
        // 次要动作按钮文本（JButton 仍用 .text）
        pullButton.text = DeployXBundle.message("toolwindow.button.pull")
        quickPushButton.text = DeployXBundle.message("toolwindow.button.quickPush")
        pullButton.toolTipText = DeployXBundle.message("toolwindow.button.pull.tooltip")
        quickPushButton.toolTipText = DeployXBundle.message("toolwindow.button.quickPush.tooltip")
        // 保存为映射：1.0.8 改为小图标按钮，只刷 tooltip
        saveAsMappingButton.toolTipText = DeployXBundle.message("toolwindow.button.saveAsMapping.tooltip")
        abortButton.text = DeployXBundle.message("toolwindow.button.abort")
        abortButton.toolTipText = DeployXBundle.message("toolwindow.button.abort.tooltip")
        openTerminalButton.toolTipText = DeployXBundle.message("toolwindow.button.openTerminal")
        browseRemoteButton.toolTipText = DeployXBundle.message("toolwindow.button.browseRemote")
        localPathField.updateBrowseTooltip(AllIcons.General.OpenDisk, DeployXBundle.message("toolwindow.button.browseLocal"))
        remotePathField.updateBrowseTooltip(AllIcons.Nodes.Deploy, DeployXBundle.message("toolwindow.tooltip.remotePathBrowse"))

        // 1.0.8：5 个路径输入框的 emptyText 占位提示跟随语言切换
        applyPathPlaceholders()
        // 同步 backup/unzip 子字段的 enabled 状态（虽然 listener 已经处理，刷新语言时再保险一次）
        updateBackupEnabled()
        updateUnzipEnabled()

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

        pageTitles().forEachIndexed { i, t -> tabBar.setTitle(i, t) }
        logToolbar?.updateActionsImmediately()
        relocalizeActionBar()
        updateSummary()
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
        // 1.0.9 重构：备份/解压子字段联动。缩进由子 panel.border 完成，禁用/启用按 selection 切换
        // （同时切换 label 与子 panel 的 enabled 状态，整个缩进区会一起灰显）
        backupCheck.addChangeListener { updateBackupEnabled() }
        unzipCheck.addChangeListener { updateUnzipEnabled() }
        updateBackupEnabled()
        updateUnzipEnabled()

        // 1.0.9：路径输入框 placeholder 占位提示
        applyPathPlaceholders()

        // 终止按钮初始禁用（无任务运行时不可点击）
        abortButton.isEnabled = false
        abortButton.toolTipText = DeployXBundle.message("toolwindow.button.abort.tooltip")

        // 1.0.8：本地路径浏览按钮，支持选文件或选目录（FileChooserDescriptor 通过
        // withFileFilter 接受 null + isForcedToShowFiles = false 实现二者皆可）
        setupLocalPathBrowser()

        // 设置远程路径浏览按钮
        remotePathField.onBrowse = onBrowse@{
            val serverId = getSelectedServerId() ?: return@onBrowse
            val server = serverManager.getServer(serverId) ?: return@onBrowse

            val currentPath = remotePathField.text.trim().ifBlank { "/" }
            val dialog = RemotePathChooserDialog(server, currentPath)
            if (dialog.showAndGet()) {
                remotePathField.text = dialog.getSelectedPath()
            }
        }
    }

    /** 1.0.9 重构：根据 backupCheck 状态切换缩进子字段的 enabled（包含 label）。 */
    private fun updateBackupEnabled() {
        // 1.1.0：渐进披露——未勾选时整行隐藏，不再置灰常驻（现状问题 #6）
        if (::backupIndentedPanel.isInitialized) backupIndentedPanel.isVisible = backupCheck.isSelected
        revalidate()
    }

    /** 1.0.9 重构：根据 unzipCheck 状态切换缩进子字段的 enabled（包含 label）。 */
    private fun updateUnzipEnabled() {
        if (::unzipIndentedPanel.isInitialized) unzipIndentedPanel.isVisible = unzipCheck.isSelected
        revalidate()
    }

    /** 1.0.9 重构：5 个路径输入框的 emptyText 占位提示。relocalize 也会再调一次以响应语言切换。 */
    private fun applyPathPlaceholders() {
        // TextFieldWithBrowseButton.getTextField() 静态返回类型是 JTextField，但构造时传入
        // 了 JBTextField，所以这里做类型转换以调用 JBTextField.getEmptyText()
        localPathField.emptyText.text = DeployXBundle.message("toolwindow.placeholder.localPath")
        remotePathField.emptyText.text = DeployXBundle.message("toolwindow.placeholder.remotePath")
        backupDirField.emptyText?.text = DeployXBundle.message("toolwindow.placeholder.backupDir")
        unzipDestField.emptyText?.text = DeployXBundle.message("toolwindow.placeholder.unzipDest")
    }

    /**
     * 1.0.8：本地路径浏览按钮。允许用户选择文件或目录（操作面板中"本地文件"也支持
     * 选择目录，部署一个目录时直接按目录方式上传）。使用 [com.intellij.openapi.fileChooser.FileChooserFactory]
     * 自定义 descriptor，关闭文件过滤、显示隐藏文件选项、允许选目录。
     */
    private fun setupLocalPathBrowser() {
        localPathField.onBrowse = {
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
            border = JBUI.Borders.empty(2, 4)
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
        }
    }

    /** 1.0.8：复制日志文本到剪贴板并提示用户。空内容给出友好提示。 */
    private fun copyLogText(text: String) {
        if (text.isBlank()) {
            logToast(DeployXBundle.message("toolwindow.log.nothingToCopy"), com.intellij.openapi.ui.MessageType.WARNING)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        // 1.1.1：提示文案改为「日志已复制」（原误用「更新报告已复制」）
        logToast(DeployXBundle.message("toolwindow.log.copiedToClipboard"), com.intellij.openapi.ui.MessageType.INFO)
    }

    /** 1.1.1：复制日志结果用工具窗口气泡提示（非模态），工具窗口不可用时退回 IDE 通知 */
    private fun logToast(text: String, type: com.intellij.openapi.ui.MessageType) {
        val twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val tw = twm.getToolWindow("DeployX")
        if (tw != null && tw.isVisible) {
            twm.notifyByBalloon(tw.id, type, text)
        } else {
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
                .createNotification(text, if (type == com.intellij.openapi.ui.MessageType.WARNING) NotificationType.WARNING else NotificationType.INFORMATION)
                .notify(project)
        }
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
        updateRerunEnabled()  // 1.1.1：任务运行中禁用历史「重新执行」
        abortButton.isEnabled = true
        taskStartedAt = System.currentTimeMillis()
        transferNotified = false
        statusArea.showRunning()
        // 运行中「日志」页签旁显示 accent 小圆点
        tabBar.setBadge(1, com.intellij.util.ui.ColorIcon(JBUI.scale(6), DeployXColors.accent))

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var result: T? = null

            override fun run(indicator: ProgressIndicator) {
                // 启动一个轻量守护线程轮询 IDEA 的 ProgressIndicator 取消状态，
                // 一旦用户点击 IDEA 进程窗口的取消按钮，立即联动触发 cancelToken。
                val indicatorWatcher = Thread({
                    // 1.1.1：任务结束时 interrupt() 会打断 sleep，安静退出，不再抛未捕获的 InterruptedException
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            if (indicator.isCanceled && !cancelToken.isCancelled()) {
                                cancelToken.cancel()
                                return@Thread
                            }
                            Thread.sleep(150)
                        }
                    } catch (_: InterruptedException) {
                        // 正常结束
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
                    updateRerunEnabled()
                    onProgressDone(cancelToken, result as T)
                    tabBar.setBadge(1, null)
                    onTaskFinished(title, cancelToken, com.alianga.idea.deploy.ui.TaskOutcome.failed(result))
                }
            }
        })
    }

    // ===================== 1.1.0：底部操作栏 + 状态区 =====================


    private fun modeAction(mode: DeployMode): AbstractAction {
        val (key, icon) = when (mode) {
            DeployMode.DEPLOY -> "toolwindow.button.startDeploy" to AllIcons.Actions.Execute
            DeployMode.QUICK_PUSH -> "toolwindow.button.quickPush" to AllIcons.Actions.Upload
            DeployMode.PREVIEW_PUSH -> "toolwindow.button.previewSync.option" to AllIcons.Actions.Preview
            DeployMode.PULL -> "toolwindow.button.pull" to AllIcons.Actions.Download
            DeployMode.PREVIEW_PULL -> "toolwindow.button.previewPull.option" to AllIcons.Actions.Preview
        }
        val shortcut = when (mode) {
            DeployMode.DEPLOY -> "DeployX.Deploy"
            DeployMode.QUICK_PUSH -> "DeployX.QuickPush"
            else -> null
        }?.let { com.intellij.openapi.keymap.KeymapUtil.getFirstKeyboardShortcutText(it) }.orEmpty()
        return object : AbstractAction(DeployXBundle.message(key), icon) {
            init { if (shortcut.isNotEmpty()) putValue(Action.SHORT_DESCRIPTION, shortcut) }
            override fun actionPerformed(e: ActionEvent) = runMode(mode)
        }
    }

    private fun runMode(mode: DeployMode) {
        // 拆分按钮主体记住上次选择的模式（与 IDE「运行」按钮一致）
        val props = com.intellij.ide.util.PropertiesComponent.getInstance()
        if (mode.push) { props.setValue(KEY_PUSH_MODE, mode.name); applyDeployMode(mode) }
        // 拉取方向不记忆模式，主体始终是「拉取」
        when (mode) {
            DeployMode.DEPLOY -> startDeploy()
            DeployMode.QUICK_PUSH -> quickPush()
            DeployMode.PREVIEW_PUSH -> previewSync()
            DeployMode.PULL -> pullFromServer()
            DeployMode.PREVIEW_PULL -> previewPull()
        }
    }


    private fun applyDeployMode(mode: DeployMode) {
        pushMode = mode
        deploySplitButton.action = modeAction(mode)
        deploySplitButton.options = arrayOf(modeAction(DeployMode.DEPLOY), modeAction(DeployMode.QUICK_PUSH), modeAction(DeployMode.PREVIEW_PUSH))
        // 1.1.1：主按钮用默认按钮（accent 蓝）样式。DEFAULT_STYLE_KEY 位于平台 darcula 包（内部 API），
        // 若后续版本移除，退化为普通样式，主次仍由位置区分
        deploySplitButton.putClientProperty(com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        deploySplitButton.toolTipText = DeployXBundle.message("toolwindow.button.startDeploy.tooltip")
        updateSummary()
    }

    private fun applyPullMode(mode: DeployMode) {
        pullMode = mode
        pullSplitButton.action = modeAction(mode)
        pullSplitButton.options = arrayOf(modeAction(DeployMode.PULL), modeAction(DeployMode.PREVIEW_PULL))
        pullSplitButton.toolTipText = DeployXBundle.message("toolwindow.button.pull.tooltip")
        updateSummary()
    }

    private fun relocalizeActionBar() {
        applyDeployMode(pushMode)
        applyPullMode(pullMode)
        statusArea.cancelButton.toolTipText = DeployXBundle.message("toolwindow.button.abort")
        statusArea.logLink.text = DeployXBundle.message("toolwindow.status.viewLog")
        statusArea.reportLink.text = DeployXBundle.message("toolwindow.status.copyReport")
        statusArea.closeLink.toolTipText = DeployXBundle.message("toolwindow.status.close")
    }


    private fun installSummaryListeners() {
        val docListener = object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = updateSummary()
        }
        localPathField.document.addDocumentListener(docListener)
        remotePathField.document.addDocumentListener(docListener)
        serverCombo.addActionListener {
            if (serverCombo.selectedItem == MANAGE_SERVERS_ITEM) {
                // 恢复原选中项后再打开设置，不触发映射带入
                suppressMappingEvents = true
                try { serverCombo.selectedIndex = lastServerIndex.coerceIn(-1, serverCombo.itemCount - 2) } finally { suppressMappingEvents = false }
                SwingUtilities.invokeLater {
                    com.alianga.idea.deploy.ui.settings.FileSyncSettingsConfigurable.open(project, com.alianga.idea.deploy.ui.settings.FileSyncSettingsConfigurable.TAB_SERVERS)
                }
                return@addActionListener
            }
            lastServerIndex = serverCombo.selectedIndex
            onServerChanged()
        }
        preCommandField.onTextChanged { updateSummary() }
        postCommandField.onTextChanged { updateSummary() }
        preCheck.addItemListener { updateCommandBlocks(); updateSummary() }
        postCheck.addItemListener { updateCommandBlocks(); updateSummary() }
        mappingCombo.addActionListener { if (!suppressMappingEvents) applySelectedMapping() }
        mappingCombo.renderer = mappingRenderer()
        localPathField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                if (!settingLocalProgrammatically) localEditedByUser = true
                refreshMappingCombo(applyDefault = true)
            }
        })
        installAutoLocalPath()
        updateCommandBlocks()
        refreshMappingCombo(applyDefault = false)
        backupCheck.addItemListener { updateSummary() }
        unzipCheck.addItemListener { updateSummary() }
        updateSummary()
    }

    /** 刷新空闲摘要；缺项时置灰两个拆分按钮并写明缺什么 */
    private fun updateSummary() {
        val serverId = getSelectedServerId()
        val server = serverId?.let { serverManager.getServer(it) }
        val title = server?.let { ServerDisplay.title(it.id, it.name) }
        val labels: (String) -> String = { DeployXBundle.message("toolwindow.summary.$it") }
        val r = OperationSummary.build(
            localPathField.text, title, remotePathField.text,
            backupCheck.isSelected, unzipCheck.isSelected,
            effectivePre(), effectivePost(), labels, pushMode
        )
        if (r.ready) {
            statusArea.headline.text = r.headline.replaceFirst(labels("deploy"), deploySplitButton.action?.getValue(Action.NAME)?.toString() ?: labels("deploy"))
            statusArea.headline.foreground = com.intellij.util.ui.UIUtil.getLabelForeground()
            statusArea.headline.icon = null
            statusArea.flow.text = r.flow
        } else {
            // 1.1.1：摘要行保持注释色（原型）；红色错误放到字段下方 + 字段红框，不只靠颜色
            val miss = r.missing.joinToString("、") { DeployXBundle.message("toolwindow.summary.missing.${it.name.lowercase()}") }
            statusArea.headline.text = DeployXBundle.message("toolwindow.summary.missing", miss)
            statusArea.headline.foreground = DeployXColors.secondary
            statusArea.headline.icon = null
            statusArea.flow.text = r.flow
        }
        val localMissing = OperationSummary.Missing.LOCAL in r.missing
        localErrorLabel.isVisible = localMissing
        localPathField.putClientProperty("JComponent.outline", if (localMissing) "error" else null)
        remotePathField.putClientProperty("JComponent.outline", if (OperationSummary.Missing.REMOTE in r.missing) "error" else null)
        localPathField.repaint(); remotePathField.repaint()
        deploySplitButton.isEnabled = r.ready
        // 拉取同样需要服务器 + 本地 + 远程
        pullSplitButton.isEnabled = r.ready
    }

    /** 任务结束：状态区显示结果行，并发一条带「查看日志」「复制报告」的平台通知 */
    private fun onTaskFinished(title: String, token: DeployCancelToken, resultFailed: Boolean? = null) {
        val text = progressLabel.text.orEmpty()
        // 1.1.1（review #28）：优先用任务结果的 success 标志；结果缺失（如异常 / 被终止）时才回退到文字判断
        val failed = resultFailed ?: (text.contains("失败") || text.contains("fail", ignoreCase = true) || text.contains("error", ignoreCase = true))
        val kind = when { token.isCancelled() -> 2; failed -> 1; else -> 0 }
        val seconds = (System.currentTimeMillis() - taskStartedAt) / 100 / 10.0
        val line = DeployXBundle.message("toolwindow.status.result", text, seconds)
        statusArea.showDone(line, kind)
        if (!transferNotified && com.alianga.idea.deploy.config.FileSyncSettings.getInstance().systemNotification) {
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
                .createNotification(title, line, when (kind) { 0 -> NotificationType.INFORMATION; 1 -> NotificationType.ERROR; else -> NotificationType.WARNING })
                .addAction(com.intellij.notification.NotificationAction.createSimple(DeployXBundle.message("toolwindow.status.viewLog")) { switchToLogTab() })
                .addAction(com.intellij.notification.NotificationAction.createSimple(DeployXBundle.message("toolwindow.status.copyReport")) { copyLastReport() })
                .notify(project)
        }
    }

    // ===================== 1.1.1：目录映射下拉与自动带入 =====================

    /** 1.1.1：同组标签统一取最长标签宽度，字段左边缘对齐（不写死像素，语言切换后重算） */
    private fun alignFormLabels() {
        val labels = listOf(localFileLabel, targetServerLabel, mappingLabel, remotePathLabel)
        labels.forEach { it.preferredSize = null }
        val w = labels.maxOf { it.preferredSize.width } + JBUI.scale(8)
        labels.forEach { it.preferredSize = Dimension(w, it.preferredSize.height) }
        // 1.1.1（review #21）：备份 / 解压目录两行同样对齐；它们在缩进 12 的子面板里，标签减去缩进，字段左边缘与上方对齐
        val indent = JBUI.scale(12)
        listOf(backupDirLabel, unzipDirLabel).forEach {
            it.preferredSize = null
            it.preferredSize = Dimension(maxOf(w - indent, it.preferredSize.width), it.preferredSize.height)
        }
    }

    private fun effectivePre(): String = if (preCheck.isSelected) preCommandField.text.trim() else ""
    private fun effectivePost(): String = if (postCheck.isSelected) postCommandField.text.trim() else ""

    private fun updateCommandBlocks() {
        if (::preBlock.isInitialized) { preBlock.isVisible = preCheck.isSelected; postBlock.isVisible = postCheck.isSelected }
        revalidate(); repaint()
    }

    /** 映射下拉渲染：下拉框本身只显示映射名（+默认）；弹层两行：名称 + 默认 / 本地 → 远程 */
    private fun mappingRenderer() = object : com.intellij.ui.ColoredListCellRenderer<MappingManager.ResolvedMapping>() {
        override fun customizeCellRenderer(list: JList<out MappingManager.ResolvedMapping>, value: MappingManager.ResolvedMapping?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            append(value.mapping.name.ifBlank { value.mapping.localDir })
            if (mappingCombo.itemCount > 1 && mappingCombo.getItemAt(0) === value) {
                append("  " + DeployXBundle.message("toolwindow.mapping.default"), com.intellij.ui.SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            if (index >= 0) {
                append("  ${value.mapping.localDir} → ${value.resolvedRemoteDir}", com.intellij.ui.SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                toolTipText = "${value.mapping.localDir} → ${value.resolvedRemoteDir}"
            }
        }
    }

    /** 按本地路径 + 当前服务器重算映射候选（最深层级在前）；applyDefault 时自动带入默认映射 */
    private fun refreshMappingCombo(applyDefault: Boolean) {
        val serverId = getSelectedServerId()
        val local = localPathField.text.trim()
        val candidates = if (serverId == null || local.isEmpty()) emptyList() else
            MappingManager.sortByPriority(MappingManager.getInstance().resolveMappingsByLocalPath(local).filter { it.mapping.serverId == serverId })
        val previous = (mappingCombo.selectedItem as? MappingManager.ResolvedMapping)?.mapping?.effectiveId
        suppressMappingEvents = true
        try {
            mappingCombo.removeAllItems()
            candidates.forEach { mappingCombo.addItem(it) }
            val keep = candidates.indexOfFirst { it.mapping.effectiveId == previous }
            if (candidates.isNotEmpty()) mappingCombo.selectedIndex = if (keep >= 0) keep else 0
        } finally { suppressMappingEvents = false }
        mappingCombo.isEnabled = candidates.isNotEmpty()
        mappingHint.text = when {
            candidates.isEmpty() && serverId != null && local.isNotEmpty() -> DeployXBundle.message("toolwindow.mapping.none")
            candidates.size > 1 -> DeployXBundle.message("toolwindow.mapping.multi", candidates.size)
            else -> ""
        }
        mappingHint.isVisible = mappingHint.text.isNotEmpty()
        val current = mappingCombo.selectedItem as? MappingManager.ResolvedMapping
        if (applyDefault && current != null && current.mapping.effectiveId != appliedMappingId) applyMapping(current)
        updateSummary()
    }

    private fun applySelectedMapping() {
        (mappingCombo.selectedItem as? MappingManager.ResolvedMapping)?.let { applyMapping(it) }
    }

    private fun reapplyCurrentMapping() = applySelectedMapping()

    /** 带入映射的远程路径与部署流程（备份 / 解压 / 前后置命令），并显示「已按映射填充 · 重置」 */
    private fun applyMapping(resolved: MappingManager.ResolvedMapping?) {
        val f = MappingAutoFill.of(resolved?.mapping, resolved?.resolvedRemoteDir)
        remotePathField.text = f.remote
        backupCheck.isSelected = f.backupOn; backupDirField.text = f.backupDir
        unzipCheck.isSelected = f.unzipOn; unzipDestField.text = f.unzipDir
        preCheck.isSelected = f.preOn; preCommandField.text = f.pre
        postCheck.isSelected = f.postOn; postCommandField.text = f.post
        appliedMappingId = resolved?.mapping?.effectiveId
        filledLabel.text = resolved?.let { DeployXBundle.message("toolwindow.mapping.filled", it.mapping.name.ifBlank { it.mapping.localDir }) }.orEmpty()
        filledRow.isVisible = resolved != null
        updateCommandBlocks()
        updateSummary()
    }

    /** 切换服务器：取该服务器默认映射带入；没有映射时清空远程路径与流程（原型 pickServer） */
    private fun onServerChanged() {
        if (suppressMappingEvents) return
        appliedMappingId = null
        refreshMappingCombo(applyDefault = true)
        if (mappingCombo.itemCount == 0 && localPathField.text.isNotBlank()) applyMapping(null)
    }

    /** 本地路径自动带入：当前编辑器文件（切换时）+ 工具窗口显示时读取项目视图选中项；用户手改过则不覆盖 */
    private fun installAutoLocalPath() {
        project.messageBus.connect(this).subscribe(
            com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun selectionChanged(event: com.intellij.openapi.fileEditor.FileEditorManagerEvent) {
                    event.newFile?.takeIf { it.isInLocalFileSystem }?.let { autoFillLocal(it.path) }
                }
            })
        addHierarchyListener { e ->
            if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isShowing) {
                val selected = runCatching {
                    com.intellij.ide.projectView.ProjectView.getInstance(project).currentProjectViewPane?.selectedElements
                        ?.firstNotNullOfOrNull { el ->
                            when (el) {
                                is com.intellij.psi.PsiFileSystemItem -> el.virtualFile
                                is com.intellij.openapi.vfs.VirtualFile -> el
                                is com.intellij.ide.util.treeView.AbstractTreeNode<*> -> (el.value as? com.intellij.psi.PsiFileSystemItem)?.virtualFile
                                else -> null
                            }
                        }
                }.getOrNull()
                    ?: com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
                selected?.takeIf { it.isInLocalFileSystem }?.let { autoFillLocal(it.path) }
            }
        }
    }

    private fun autoFillLocal(path: String) {
        if (localEditedByUser && localPathField.text.isNotBlank()) return
        if (localPathField.text == path) return
        settingLocalProgrammatically = true
        try { localPathField.text = path } finally { settingLocalProgrammatically = false }
    }

    private fun refreshServerCombo() {
        // 1.1.1：重建列表期间屏蔽「切换服务器 → 带入映射」，避免设置页增删服务器时清掉用户填写的字段；保留原选中项
        val previousId = getSelectedServerId()
        suppressMappingEvents = true
        try { repopulateServerCombo(previousId) } finally { suppressMappingEvents = false }
        refreshMappingCombo(applyDefault = false)
    }

    private fun repopulateServerCombo(previousId: String?) {
        serverCombo.removeAllItems()
        val servers = serverManager.getServers()
        for (server in servers) {
            // 1.1.0：条目值只存 id，显示交给 ServerComboRenderer（去掉「164 - 164」重复）
            serverCombo.addItem(server.id)
        }
        serverCombo.addItem(MANAGE_SERVERS_ITEM)
        val defaultServer = serverManager.getDefaultServer()
        if (defaultServer != null) {
            val index = servers.indexOfFirst { it.id == defaultServer.id }
            if (index >= 0) serverCombo.selectedIndex = index
        }
        previousId?.let { id -> servers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { serverCombo.selectedIndex = it } }
        if (servers.isEmpty()) serverCombo.selectedIndex = -1
        lastServerIndex = serverCombo.selectedIndex
    }


    private fun esc(t: String) = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(t)
    private fun hex(c: java.awt.Color) = "#" + com.intellij.ui.ColorUtil.toHex(c)
    private fun typeLabel(r: HistoryRecord) = DeployXBundle.message("history.type." + r.type.value)
    private fun statusOf(r: HistoryRecord) = when (r.status) {
        HistoryRecord.OperationStatus.SUCCESS -> DeployXBundle.message("toolwindow.history.status.success") to DeployXColors.success
        HistoryRecord.OperationStatus.FAILED -> DeployXBundle.message("toolwindow.history.status.failed") to DeployXColors.error
        HistoryRecord.OperationStatus.CANCELLED -> DeployXBundle.message("toolwindow.history.status.cancelled") to DeployXColors.warning
    }

    private fun historyRowRenderer(list: JList<*>, index: Int, isSelected: Boolean): java.awt.Component {
        val r = historyRecords.getOrNull(index) ?: return JPanel()
        val fg = if (isSelected) list.selectionForeground else list.foreground
        val bg = if (isSelected) list.selectionBackground else list.background
        val (statusText, color) = statusOf(r)
        val day = com.alianga.idea.deploy.ui.HistoryFilter.day(r.timestamp)
        val prevDay = historyRecords.getOrNull(index - 1)?.let { com.alianga.idea.deploy.ui.HistoryFilter.day(it.timestamp) }
        val tag = if (com.alianga.idea.deploy.ui.HistoryFilter.rollbackable(r))
            " <font color='${hex(DeployXColors.link)}'>" + esc(DeployXBundle.message("toolwindow.history.filter.rollbackable")) + "</font>" else ""
        val path = if (r.type == HistoryRecord.OperationType.PULL) "${r.targetPath} → ${r.sourcePath}" else "${r.sourcePath} → ${r.targetPath}"
        val row1 = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("<html><nobr><b>${esc(typeLabel(r))}</b> ${esc(ServerDisplay.title(r.serverId, r.serverName))}$tag</nobr></html>").apply {
                foreground = fg; minimumSize = java.awt.Dimension(0, 0)
            }, BorderLayout.CENTER)
            add(JBLabel("<html><font color='${hex(color)}'>${esc(statusText)}</font> ${esc(r.formattedTime)}</html>").apply { foreground = fg }, BorderLayout.EAST)
        }
        // 纯文本标签：超出宽度时以 … 截断（HTML 标签不会截断）
        val row2 = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel("●").apply { foreground = color }, BorderLayout.WEST)
            add(JBLabel(path).apply {
                foreground = if (isSelected) fg else DeployXColors.secondary
                toolTipText = path; minimumSize = java.awt.Dimension(0, 0)
            }, BorderLayout.CENTER)
        }
        val card = object : JPanel(BorderLayout()) {
            // 首选宽度不超过列表可见宽度，避免撑宽列表
            override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().also { d ->
                val w = list.visibleRect.width
                if (w > 0) d.width = minOf(d.width, w)
            }
        }.apply {
            background = bg; border = JBUI.Borders.empty(4, 8)
            add(row1, BorderLayout.NORTH); add(row2, BorderLayout.SOUTH)
        }
        if (day == prevDay) return card
        return JPanel(BorderLayout()).apply {
            background = list.background
            add(com.intellij.ui.TitledSeparator(DeployXBundle.message("toolwindow.history.day." + day.name.lowercase())), BorderLayout.NORTH)
            add(card, BorderLayout.CENTER)
        }
    }

    private fun selectedHistoryRecord(): HistoryRecord? = historyRecords.getOrNull(historyList.selectedIndex)

    private fun buildHistoryDetailPanel(): JComponent {
        hdRollbackBtn.addActionListener { rollbackFromHistory() }
        hdRerunBtn.addActionListener { rerunFromHistory() }
        hdCopyBtn.addActionListener { copyReportFromHistory() }
        hdExportBtn.addActionListener { exportReportFromHistory() }
        val logBtn = JButton(AllIcons.Debugger.Console).apply {
            toolTipText = DeployXBundle.message("history.detail.viewLog"); addActionListener { switchToLogTab() }
        }
        val deleteBtn = JButton(AllIcons.Actions.GC).apply {
            toolTipText = DeployXBundle.message("history.detail.delete"); addActionListener { deleteHistoryRecord() }
        }
        val moreBtn = JButton(DeployXBundle.message("history.detail.more"), AllIcons.General.ArrowDown).apply { horizontalTextPosition = SwingConstants.LEFT }
        moreBtn.addActionListener {
            val group = DefaultActionGroup(historyFillConfigAction, historyViewDetailAction)
            JBPopupFactory.getInstance().createActionGroupPopup(null, group,
                DataManager.getInstance().getDataContext(moreBtn), JBPopupFactory.ActionSelectionAid.MNEMONICS, true)
                .showUnderneathOf(moreBtn)
        }
        val buttons = JPanel(WrapLayout(FlowLayout.LEADING, JBUI.scale(4), JBUI.scale(2))).apply {
            listOf(hdRollbackBtn, hdRerunBtn, hdCopyBtn, hdExportBtn, moreBtn, logBtn, deleteBtn).forEach { add(it) }
        }
        val info = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(hdHeader, hdRows, hdSteps, hdRollbackReason).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
        }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(6, 8)
            add(info, BorderLayout.NORTH)
            add(JBScrollPane(hdFiles), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun updateHistoryDetail() {
        val r = selectedHistoryRecord()
        listOf(hdRollbackBtn, hdRerunBtn, hdCopyBtn, hdExportBtn).forEach { it.isEnabled = r != null }
        if (r == null) {
            hdHeader.text = ""; hdRows.text = ""; hdSteps.text = ""; hdFiles.text = ""; hdRollbackReason.text = ""
            return
        }
        val (statusText, color) = statusOf(r)
        val note = DeployXBundle.message("history.detail.note", r.fileCount, r.formattedSize)
        hdHeader.text = "<html><b>${esc(typeLabel(r))}</b> <font color='${hex(color)}'>${esc(statusText)}</font>" +
            " · ${esc(r.formattedDuration)} · <font color='${hex(DeployXColors.secondary)}'>${esc(note)}</font></html>"
        val backup = com.alianga.idea.deploy.ui.HistoryDetail.backupText(r, DeployXBundle.message("history.detail.backupDisabled"))
        val mapping = com.alianga.idea.deploy.ui.HistoryDetail.mappingText(r) {
            MappingManager.getInstance().resolveMappingByLocalPath(r.sourcePath)?.mapping?.let { it.name.ifBlank { it.id } }
        }
        val rows = listOf(
            "history.detail.k.server" to "${ServerDisplay.title(r.serverId, r.serverName)} ${r.serverAddress}".trim(),
            "history.detail.k.mapping" to mapping,
            "history.detail.k.local" to r.sourcePath.ifBlank { "—" },
            "history.detail.k.remote" to r.targetPath.ifBlank { "—" },
            "history.detail.k.backup" to backup
        )
        hdRows.text = "<html><table cellpadding='1'>" + rows.joinToString("") { (k, v) ->
            "<tr><td><font color='${hex(DeployXColors.secondary)}'>${esc(DeployXBundle.message(k))}</font></td><td>${esc(v)}</td></tr>"
        } + "</table></html>"
        hdSteps.text = com.alianga.idea.deploy.ui.HistoryDetail.steps(r).joinToString("  →  ") { (step, mark) ->
            mark.symbol + " " + DeployXBundle.message("history.detail.step." + step.name.lowercase())
        } + if (com.alianga.idea.deploy.ui.HistoryDetail.stepsInferred(r)) " " + DeployXBundle.message("history.detail.steps.inferredShort") else ""
        // 1.1.1：旧记录的步骤链是推断的，界面直接标「（推断）」，tooltip 给完整说明
        hdSteps.toolTipText = if (com.alianga.idea.deploy.ui.HistoryDetail.stepsInferred(r)) DeployXBundle.message("history.detail.steps.inferred") else null
        val files = com.alianga.idea.deploy.ui.HistoryDetail.files(r)
        hdFiles.text = if (files.isEmpty()) DeployXBundle.message("toolwindow.history.detail.noFiles")
            else DeployXBundle.message("toolwindow.history.detail.updatedFilesTitle", files.size) + "\n" + files.joinToString("\n")
        hdFiles.caretPosition = 0
        val block = com.alianga.idea.deploy.ui.HistoryFilter.rollbackBlockReason(r)
        val reason = block?.let { DeployXBundle.message("toolwindow.history.rollbackBlocked." + it.name.lowercase()) }
        hdRollbackBtn.isEnabled = block == null
        hdRollbackBtn.toolTipText = reason ?: DeployXBundle.message("toolwindow.history.rollback.tooltip")
        hdRollbackReason.text = reason?.let { DeployXBundle.message("history.detail.rollbackUnavailable", it) } ?: ""
        hdRollbackReason.isVisible = reason != null
        val rerun = com.alianga.idea.deploy.ui.HistoryDetail.rerun(r)
        hdRerunBtn.text = DeployXBundle.message(com.alianga.idea.deploy.ui.HistoryDetail.rerunLabelKey(r))
        updateRerunEnabled()
    }

    /** 重新执行：按记录类型调用对应动作（部署 / 推送 / 拉取），失败记录为「重试」 */
    /** 1.1.1：重新执行——记录类型不支持或有任务在运行时禁用，tooltip 写明原因 */
    private fun updateRerunEnabled() {
        val r = selectedHistoryRecord()
        val supported = r != null && com.alianga.idea.deploy.ui.HistoryDetail.rerun(r) != com.alianga.idea.deploy.ui.HistoryDetail.Rerun.NONE
        val busy = currentCancelToken != null
        hdRerunBtn.isEnabled = supported && !busy
        hdRerunBtn.toolTipText = when {
            r == null -> null
            !supported -> DeployXBundle.message("history.detail.rerun.noneTip")
            busy -> DeployXBundle.message("history.detail.rerun.busyTip")
            else -> null
        }
    }

    private fun rerunFromHistory() {
        if (currentCancelToken != null) return
        val r = selectedHistoryRecord() ?: return
        if (!java.io.File(r.sourcePath).exists()) {
            Messages.showWarningDialog(DeployXBundle.message("toolwindow.log.localFileNotFound", r.sourcePath), DeployXBundle.message("toolwindow.log.redeployTitle"))
            return
        }
        val plan = com.alianga.idea.deploy.ui.RedeployPlan
        // 1.1.1：批量记录（右键部署 / 推送）重建与原操作相同的条目，走同一条 deployBatch / uploadBatch 代码路径，
        // 避免 rsync 源路径缺结尾斜杠导致远程嵌套 lib/lib
        val mapping = MappingManager.getInstance().getMappings().firstOrNull {
            it.serverId == r.serverId && it.localDir.trimEnd('/') == r.sourcePath.trimEnd('/') && it.remoteDir.trimEnd('/') == r.targetPath.trimEnd('/')
        }
        when (com.alianga.idea.deploy.ui.HistoryDetail.rerun(r)) {
            com.alianga.idea.deploy.ui.HistoryDetail.Rerun.PUSH ->
                if (plan.isBatch(r)) executeUploadBatch(plan.uploadItems(r, mapping?.effectiveId.orEmpty()))
                else executePush(r.sourcePath, r.serverId)
            com.alianga.idea.deploy.ui.HistoryDetail.Rerun.DEPLOY ->
                if (plan.isBatch(r)) executeDeployBatch(plan.deployItems(r, mapping?.effectiveId.orEmpty(), mapping?.backupSource?.ifBlank { null }))
                else redeployFromHistory()
            com.alianga.idea.deploy.ui.HistoryDetail.Rerun.NONE -> Unit
            // 拉取：DeployService.redeploy 内按类型分派到 redeployPull（本身按相对路径重建）
            else -> redeployFromHistory()
        }
    }

    /** 删除单条历史记录（HistoryManager.deleteRecord），先确认 */
    private fun deleteHistoryRecord() {
        val r = selectedHistoryRecord() ?: return
        val ok = Messages.showYesNoDialog(project, DeployXBundle.message("history.detail.delete.confirm"), DeployXBundle.message("history.detail.delete"),
            Messages.getYesButton(), Messages.getNoButton(), Messages.getWarningIcon()) == Messages.YES
        if (!ok) return
        historyManager.deleteRecord(r.id)
        refreshHistory()
    }

    private fun refreshHistory() {
        val all = historyManager.getRecords()
        val query = historySearchField.text.orEmpty()
        // historyRecords 保存「过滤后」的列表，与列表索引一一对应，原有按索引取记录的动作无需改动
        historyRecords = com.alianga.idea.deploy.ui.HistoryFilter.apply(all, historyFilterKind, query)
        val counts = com.alianga.idea.deploy.ui.HistoryFilter.counts(all, query)
        historyChips.forEach { (k, chip) ->
            chip.text = DeployXBundle.message("toolwindow.history.filter." + k.name.lowercase()) + " " + (counts[k] ?: 0)
        }
        val keepId = historyList.selectedIndex.takeIf { it in historyRecordsBefore.indices }?.let { historyRecordsBefore[it].id }
        historyRecordsBefore = historyRecords
        historyListModel.clear()
        for (record in historyRecords) {
            historyListModel.addElement(record.summary)
        }
        // 1.1.1：保持原选中；没有则默认选中第一条，详情区常驻
        if (historyRecords.isNotEmpty()) {
            historyList.selectedIndex = historyRecords.indexOfFirst { it.id == keepId }.takeIf { it >= 0 } ?: 0
        }
        updateHistoryDetail()
        // 空状态区分：一条都没有 / 过滤后没有匹配
        historyEmptyLabel.text = DeployXBundle.message(if (all.isEmpty()) "history.empty.none" else "history.empty.noMatch")
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
            // 1.1.0：写明不可回滚的具体原因（非部署记录 / 失败的部署 / 未启用备份）
            val blockReason = com.alianga.idea.deploy.ui.HistoryFilter.rollbackBlockReason(record)
            if (blockReason != null) {
                Messages.showWarningDialog(
                    DeployXBundle.message("toolwindow.history.rollbackBlocked." + blockReason.name.lowercase()),
                    DeployXBundle.message("toolwindow.log.rollbackTitle")
                )
                return
            }

            // 显示回滚确认对话框
            val newer = com.alianga.idea.deploy.service.RollbackCommands.newerSuccessfulCount(record, historyManager.getRecords())
            val dialog = RollbackDialog(project, record, newerCount = newer)
            if (!dialog.showAndGet()) {
                return
            }

            // 执行回滚
            // 执行后切到日志页（回滚进度对话框保留）
            switchToLogTab()
            val progressDialog = RollbackProgressDialog(project, record, options = dialog.options, onComplete = { result ->
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
            })
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
        preCheck.isSelected = record.preCommand.isNotBlank()
        postCheck.isSelected = record.postCommand.isNotBlank()

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

        selectTab(0) // 切换到操作 tab
        appendLog(DeployXBundle.message("toolwindow.log.configFilled"))
    }

    private fun clearHistory() {
        // 1.1.0：清空不可恢复，先确认（默认焦点在「否」）
        val ok = Messages.showYesNoDialog(
            project, DeployXBundle.message("toolwindow.history.clear.confirm"), DeployXBundle.message("toolwindow.history.clear"),
            Messages.getYesButton(), Messages.getNoButton(), Messages.getWarningIcon()
        ) == Messages.YES
        if (!ok) return
        historyManager.clearHistory()
        refreshHistory()
    }

    private fun getSelectedServerId(): String? {
        return serverCombo.selectedItem?.toString()?.takeIf { it != MANAGE_SERVERS_ITEM }
    }

    fun selectScriptTab() {
        selectTab(3)
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
        (if (preCommand) preCheck else postCheck).isSelected = true // 插入脚本后自动展开对应命令框
        val target = if (preCommand) preCommandField else postCommandField
        target.text = if (target.text.isBlank()) {
            command.trim()
        } else {
            target.text.trimEnd() + "\n" + command.trim()
        }
        selectTab(0)
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
        transferNotified = true
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
        val result = chooser.showSaveDialog(pages.firstOrNull() ?: this)
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
        CopyPasteManager.getInstance().setContents(StringSelection(com.alianga.idea.deploy.ui.ReportExport.reconcile(historyRecords[idx], reportText)))
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
            selectedFile = java.io.File(com.alianga.idea.deploy.ui.ReportExport.defaultFileName(record))
        }
        val result = chooser.showSaveDialog(pages.firstOrNull() ?: this)
        if (result == JFileChooser.APPROVE_OPTION) {
            // 1.1.1：与「复制报告」同一份文本，UTF-8 写出
            com.alianga.idea.deploy.ui.ReportExport.write(chooser.selectedFile, com.alianga.idea.deploy.ui.ReportExport.reconcile(record, reportText))
            Messages.showInfoMessage(DeployXBundle.message("toolwindow.report.exported", chooser.selectedFile.absolutePath), DeployXBundle.message("toolwindow.report.export.title"))
        }
    }

    private fun configureLogArea(area: JBTextArea) {
        // 1.1.0：日志空状态文案
        area.emptyText.text = DeployXBundle.message("toolwindow.log.empty")
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
        if (selectedPage != 1) selectTab(1)
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
        // 1.0.8：同一服务器匹配到多条嵌套（父子目录）映射时，允许用户手动选择映射（含父目录映射）
        val mappingChoice = choosePreferredMapping(localPath, serverId)
        if (mappingChoice.cancelled) return
        val resolved = MappingManager.pickResolvedByServer(
            MappingManager.getInstance().resolveMappingsByLocalPath(localPath, isDirectory),
            serverId,
            mappingChoice.mappingId
        )
        // remoteBaseDir 用映射的远程根目录（而非 resolvedRemoteDir）：relativePath 是相对
        // mapping.localDir 的完整相对路径，与 DownloadItem.fullRemotePath 的
        // remoteBaseDir + relativePath 约定对齐（与右键 PullFromServerAction 一致）；
        // 若用 resolvedRemoteDir（已含 relativePath 的父级部分），多级相对路径会重复计数。
        val (localBaseDir, remoteBaseDir, relativePath) = if (resolved != null) {
            Triple(resolved.mapping.localDir, resolved.mapping.remoteDir, resolved.relativePath)
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
            preCommand = effectivePre().ifBlank { null },
            postCommand = effectivePost().ifBlank { null }
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

        // 1.0.8：同一服务器匹配到多条嵌套（父子目录）映射时，允许用户手动选择映射（含父目录映射）
        val mappingChoice = choosePreferredMapping(localPath, serverId)
        if (mappingChoice.cancelled) return

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
                    preferredMappingId = mappingChoice.mappingId,
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
     * 1.0.8 新增：手动操作流程（快速推送/拉取）中的映射选择。
     *
     * 本地路径在当前服务器上匹配到多条嵌套（父子目录）映射时，弹出映射选择对话框
     * 让用户手动选择（默认仍为最深层级映射）；无歧义时不弹窗。
     * 用户在对话框中取消时 `cancelled = true`，调用方应中止本次操作。
     *
     * @param localPath 操作面板中的本地路径
     * @param serverId 操作面板中选中的服务器 ID（null 时不做选择）
     */
    private fun choosePreferredMapping(localPath: String, serverId: String?): MappingChoice {
        if (serverId == null) return MappingChoice(cancelled = false, mappingId = null)
        val candidates = MappingManager.getInstance().resolveMappingsByLocalPath(localPath)
            .filter { it.mapping.serverId == serverId }
        if (candidates.size <= 1) return MappingChoice(cancelled = false, mappingId = null)
        // 1.1.1：不再弹 MappingSelectionDialog——映射选择已移到操作页「目录映射」下拉（实施方案 3.4.1），
        // 直接使用下拉当前选中项；选中项不属于该服务器时回退最深层级
        val chosen = (mappingCombo.selectedItem as? MappingManager.ResolvedMapping)
            ?.takeIf { sel -> candidates.any { it.mapping.effectiveId == sel.mapping.effectiveId } }
        return MappingChoice(cancelled = false, mappingId = chosen?.mapping?.effectiveId)
    }

    /** 1.0.8：手动流程映射选择结果（cancelled 表示用户在对话框中取消，应中止操作） */
    private data class MappingChoice(val cancelled: Boolean, val mappingId: String?)

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
            preCommandEnabled = preCheck.isSelected && preCommandField.text.trim().isNotBlank(),
            preCommand = preCommandField.text.trim(),
            postCommandEnabled = postCheck.isSelected && postCommandField.text.trim().isNotBlank(),
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
