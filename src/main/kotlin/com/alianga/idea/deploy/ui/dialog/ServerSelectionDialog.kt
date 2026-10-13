package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 服务器选择对话框 - 用于右键菜单选择目标服务器
 *
 * 特性：
 * - 支持实时搜索过滤（按 id / 名称 / 主机 / 用户名 匹配）
 * - 适配较多服务器场景，列表尺寸加大
 * - 对话框打开后，键盘输入字母/数字会自动聚焦到搜索框并输入（无需先点击搜索框）
 * - 1.0.8：同一服务器匹配到多条嵌套（父子目录）映射时，展示映射选择面板，
 *   允许用户手动改选次优先级（父目录）映射；默认仍为最深层级（子目录）映射
 */
class ServerSelectionDialog(
    private val servers: List<ServerConfig>,
    private val titleText: String = DeployXBundle.message("dialog.server.select.title"),
    private val messageText: String = DeployXBundle.message("dialog.server.select.message"),
    private val showCommandOptions: Boolean = false,
    private val commandAvailabilityByServerId: Map<String, CommandAvailability> = emptyMap(),
    /**
     * 1.0.8：每台服务器的映射候选（同一路径匹配到多条嵌套映射时非空）。
     *
     * key = serverId，value = 该服务器上选中文件匹配到的全部映射（含父子嵌套）。
     * 为空 Map 时（如 SSH 终端选择）不展示映射面板，行为与旧版一致。
     */
    private val resolvedByServerId: Map<String, List<MappingManager.ResolvedMapping>> = emptyMap(),
    /** 1.1.0：确认按钮的具体动词（部署 / 推送 / 拉取 / 预览），null 时用平台默认 */
    private val okText: String? = null,
    /** 1.1.0：因没有匹配映射而被隐藏的服务器数，>0 时底部显示提示与「添加映射…」 */
    private val hiddenServerCount: Int = 0
) : DialogWrapper(null) {

    /** 1.1.0：页脚「已选 N 台服务器」 */
    private val selectionCountLabel = JBLabel().apply { foreground = com.alianga.idea.deploy.ui.DeployXColors.secondary }

    data class CommandAvailability(
        val hasPreCommand: Boolean = false,
        val hasPostCommand: Boolean = false
    )

    private val listModel = CollectionListModel(servers.map { formatServer(it) })
    private val serverList = JBList(listModel)
    private val searchField = JBTextField()
    private val executePreCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePreCommand"), false)
    private val executePostCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePostCommand"), false)

    /**
     * 1.0.8：服务器列表滚动容器（提升为字段，首选高度随映射面板可见性动态调整，
     * 避免映射面板把弹窗撑得过高，兼顾小屏）。
     */
    private val serverScrollPane = JBScrollPane(serverList).apply {
        preferredSize = JBUI.size(
            JBUI.scale(LIST_WIDTH),
            JBUI.scale(SERVER_LIST_HEIGHT_NORMAL)
        )
    }

    // 1.0.8：映射选择面板（同一服务器匹配到多条嵌套父子映射时展示）
    private val mappingLabel = JBLabel(DeployXBundle.message("dialog.server.select.label.mapping"))
    private val mappingHintLabel = JBLabel(DeployXBundle.message("dialog.server.select.mapping.hint")).apply {
        // 提示文案较长，启用自动换行，避免把对话框撑宽
        setAllowAutoWrapping(true)
    }
    private val mappingListModel = CollectionListModel<MappingManager.ResolvedMapping>(emptyList())
    private val mappingList = JBList(mappingListModel).apply {
        // 1.0.8：长路径条目中间省略 + tooltip 展示全文，避免文本被裁切
        cellRenderer = MappingListCellRenderer()
    }
    /** 1.0.8：映射列表滚动容器（首选高度按候选行数动态设置） */
    private val mappingScrollPane = JBScrollPane(mappingList)
    private val mappingSectionPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
        add(mappingLabel, BorderLayout.NORTH)
        add(mappingScrollPane, BorderLayout.CENTER)
        add(mappingHintLabel, BorderLayout.SOUTH)
    }

    /** 1.0.8：每台服务器上用户手动选择的映射 ID（effectiveId），切换服务器选择时保留 */
    private val mappingChoiceByServerId = mutableMapOf<String, String>()

    /** 1.0.8：当前映射面板展示的候选（按优先级排序，最深层级在前） */
    private var currentMappingCandidates: List<MappingManager.ResolvedMapping> = emptyList()

    /** 1.0.8：上一次加载映射面板的服务器 ID（切换选择前用于保存其映射选择） */
    private var lastMappingLeadServerId: String? = null

    /** 当前过滤后展示的服务器列表（搜索框为空时等于 servers） */
    private var filteredServers: List<ServerConfig> = servers

    /** 键盘事件分发器：对话框可见时，按字母/数字键自动把焦点路由到搜索框 */
    private var keyDispatcher: KeyEventDispatcher? = null

    var selectedServer: ServerConfig? = null
        private set

    /** 多选模式：选中的服务器列表（单选时只有一个元素） */
    var selectedServers: List<ServerConfig> = emptyList()
        private set

    /**
     * 1.0.8：每台服务器最终使用的映射 ID（serverId -> mapping effectiveId）。
     * 嵌套映射歧义时值为用户选择（或默认的最深层级映射）；单候选服务器为其唯一映射。
     */
    var selectedMappingIdsByServerId: Map<String, String> = emptyMap()
        private set

    /** 1.0.8：单选场景下选中的映射 ID（多选取第一台服务器的选择），无歧义时为 null */
    var selectedMappingId: String? = null
        private set

    var executePreCommand: Boolean = false
        private set

    var executePostCommand: Boolean = false
        private set

    init {
        title = titleText
        okText?.let { setOKButtonText(it) }
        serverList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        if (servers.isNotEmpty()) {
            // 1.1.0：默认勾选默认服务器
            serverList.selectedIndex = servers.indexOfFirst { it.isDefault }.coerceAtLeast(0)
        }
        serverList.addListSelectionListener { if (!it.valueIsAdjusting) updateSelectionCount() }
        // 1.1.0：列表高度按内容自适应（最多 8 行后滚动），不再固定占半屏（现状问题 #10）
        serverList.visibleRowCount = servers.size.coerceIn(1, 8)
        serverScrollPane.preferredSize = null
        serverList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateCommandOptions()
                updateMappingPanel()
            }
        }
        // 双击列表项直接确认选择（与 RemotePathChooserDialog 行为一致）
        serverList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && filteredServers.isNotEmpty()) {
                    doOKAction()
                }
            }
        })
        mappingList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // 双击映射项直接确认选择（与服务器列表行为一致）
        mappingList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && currentMappingCandidates.isNotEmpty()) {
                    doOKAction()
                }
            }
        })

        // 搜索框：占位提示文本
        searchField.emptyText.text = DeployXBundle.message("dialog.server.select.search.placeholder")
        // 实时过滤
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterList()
            override fun removeUpdate(e: DocumentEvent) = filterList()
            override fun changedUpdate(e: DocumentEvent) = filterList()
        })
        // 在搜索框按 Enter：若仅一个匹配则直接确认，否则把焦点交给列表
        searchField.addActionListener {
            if (filteredServers.size == 1) {
                serverList.selectedIndex = 0
                doOKAction()
            } else if (filteredServers.isNotEmpty()) {
                serverList.requestFocusInWindow()
            }
        }

        init()
        initValidation()
        updateCommandOptions()
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        val n = serverList.selectedIndices.size
        selectionCountLabel.text = DeployXBundle.message("dialog.server.select.selectedCount", n)
    }

    /** 1.1.0：未选择时红字提示且主按钮不可用（错误不只靠颜色，带文字） */
    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (serverList.selectedIndices.isEmpty())
            com.intellij.openapi.ui.ValidationInfo(DeployXBundle.message("dialog.server.select.atLeastOne"), serverList)
        else null

    override fun createCenterPanel(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(messageText))
            .addVerticalGap(JBUI.scale(6))
        // 1.1.0：服务器很少（≤5 台）时隐藏搜索框
        if (servers.size > 5) builder.addComponent(searchField).addVerticalGap(JBUI.scale(4))
        builder.addComponent(serverScrollPane)
        if (hiddenServerCount > 0) {
            val hiddenRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 0, 0)).apply {
                add(JBLabel(DeployXBundle.message("dialog.server.select.hiddenCount", hiddenServerCount)).apply {
                    foreground = com.alianga.idea.deploy.ui.DeployXColors.secondary
                    font = JBUI.Fonts.smallFont()
                    border = JBUI.Borders.emptyRight(8)
                })
                add(com.intellij.ui.components.ActionLink(DeployXBundle.message("dialog.server.select.addMapping")) {
                    close(CANCEL_EXIT_CODE)
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(null as com.intellij.openapi.project.Project?, "DeployX")
                })
            }
            builder.addVerticalGap(JBUI.scale(4)).addComponent(hiddenRow)
        }
        builder
            .addVerticalGap(JBUI.scale(8))
            // 1.0.8：映射选择面板（无歧义时不可见，GridBagLayout 不为其占位）
            .addComponent(mappingSectionPanel)

        if (showCommandOptions) {
            builder
                .addVerticalGap(JBUI.scale(8))
                .addComponent(executePreCommandCheck)
                .addComponent(executePostCommandCheck)
        }

        // 1.0.8 布局修复：不再写死 (520, 400/500) 的首选尺寸。
        // 该固定高度小于「服务器列表 + 映射面板」的自然高度，GridBagLayout 为塞进该尺寸
        // 会压缩可收缩的映射面板，导致映射列表只剩一行、候选映射看不到、条目文本被裁切。
        // 现由两个滚动面板各自的首选尺寸自然决定弹窗大小：无歧义时约 350px 高；
        // 有歧义时按映射候选行数（2~5 行）增长，不同界面语言/字体/屏幕尺寸下均完整展示。
        builder.addVerticalGap(JBUI.scale(8)).addComponent(selectionCountLabel)
        val panel = builder.panel.apply { minimumSize = JBUI.size(480, 200) }

        // 注册键盘事件路由：对话框可见时，按字母/数字键自动把焦点转到搜索框
        registerKeyRouting()

        // 1.0.8：按默认选中的服务器初始化映射面板可见性、候选列表与各列表尺寸
        updateMappingPanel()

        // 默认让搜索框获得焦点，方便直接输入
        SwingUtilities.invokeLater { searchField.requestFocusInWindow() }

        return panel
    }

    /**
     * 注册全局键盘事件分发器：对话框可见时，若焦点不在任何文本组件上，
     * 按字母/数字键会自动把焦点路由到搜索框并输入字符（无需先点击搜索框）。
     */
    private fun registerKeyRouting() {
        // 先移除可能残留的 dispatcher（避免重复注册）
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        keyDispatcher = KeyEventDispatcher { e ->
            // 仅处理 KEY_TYPED（产生可输入字符的事件），且对话框必须可见
            if (e.id != KeyEvent.KEY_TYPED || !isShowing) {
                return@KeyEventDispatcher false
            }
            // 焦点已经在搜索框或任意文本组件时，让该组件正常处理（不拦截）
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner === searchField || focusOwner is JTextComponent) {
                return@KeyEventDispatcher false
            }
            val c = e.keyChar
            // 仅路由字母数字和常见的 id/主机连接符，避免拦截 Enter、Esc、Tab、方向键等控制键
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                searchField.requestFocusInWindow()
                // 焦点转移是异步的，同步把字符追加到搜索框，避免字符丢失
                searchField.text += c.toString()
                searchField.caretPosition = searchField.text.length
                // 消费事件，阻止默认分发
                return@KeyEventDispatcher true
            }
            false
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
    }

    override fun dispose() {
        // 移除键盘事件分发器，避免内存泄漏
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            keyDispatcher = null
        }
        super.dispose()
    }

    override fun doOKAction() {
        // 1.0.8：保存当前 lead 服务器上用户已做出的映射选择
        saveCurrentMappingChoice()

        // 多选模式：收集所有选中的服务器
        val selectedIndices = serverList.selectedIndices
        selectedServers = selectedIndices.filter { it >= 0 && it < filteredServers.size }
            .map { filteredServers[it] }
        // 向后兼容：selectedServer 取第一个
        selectedServer = selectedServers.firstOrNull()
        executePreCommand = showCommandOptions && executePreCommandCheck.isEnabled && executePreCommandCheck.isSelected
        executePostCommand = showCommandOptions && executePostCommandCheck.isEnabled && executePostCommandCheck.isSelected

        // 1.0.8：汇总每台服务器最终使用的映射 ID
        // - 单候选服务器：直接使用其唯一映射
        // - 多候选服务器：使用用户选择；未选择则回退最深层级（自动匹配项）
        selectedMappingIdsByServerId = selectedServers.mapNotNull { server ->
            val candidates = resolvedByServerId[server.id].orEmpty()
            when {
                candidates.isEmpty() -> null
                candidates.size == 1 -> server.id to candidates.first().mapping.effectiveId
                else -> {
                    val remembered = mappingChoiceByServerId[server.id]
                    val chosen = remembered
                        ?.let { id -> candidates.firstOrNull { it.mapping.effectiveId == id } }
                        ?: MappingManager.sortByPriority(candidates).first()
                    server.id to chosen.mapping.effectiveId
                }
            }
        }.toMap()
        selectedMappingId = selectedMappingIdsByServerId.values.firstOrNull()

        super.doOKAction()
    }

    private fun updateCommandOptions() {
        if (!showCommandOptions) return
        val selectedIndex = serverList.selectedIndex
        val server = filteredServers.getOrNull(selectedIndex)
        val availability = server?.let { commandAvailabilityByServerId[it.id] } ?: CommandAvailability()

        executePreCommandCheck.isEnabled = availability.hasPreCommand
        executePostCommandCheck.isEnabled = availability.hasPostCommand
        if (!availability.hasPreCommand) executePreCommandCheck.isSelected = false
        if (!availability.hasPostCommand) executePostCommandCheck.isSelected = false
    }

    /**
     * 1.0.8：刷新映射选择面板——展示当前选中（lead）服务器的映射候选。
     *
     * 仅当该服务器有 >=2 条候选映射（嵌套父子目录映射竞争）时展示面板；
     * 切换服务器选择前会先保存上一台服务器上用户已做出的映射选择。
     *
     * 布局：映射面板可见时按候选行数（2~5 行）设置映射列表高度，并适当压缩服务器列表，
     * 保证候选映射完整可见的同时控制弹窗总高度（小屏适配）。
     */
    private fun updateMappingPanel() {
        // 保存上一台服务器上用户已做出的映射选择
        // （此时 serverList.selectedIndex 已变为新服务器，必须用 lastMappingLeadServerId 归属）
        saveCurrentMappingChoice()

        val server = filteredServers.getOrNull(serverList.selectedIndex)
        val candidates = server?.let { resolvedByServerId[it.id] }.orEmpty()
        currentMappingCandidates = MappingManager.sortByPriority(candidates)

        val hasChoices = currentMappingCandidates.size > 1
        mappingSectionPanel.isVisible = hasChoices
        // 映射面板可见时压缩服务器列表高度，隐藏时恢复，避免弹窗过高
        // 1.1.0：服务器列表高度按行数自适应，映射面板可见时最多 4 行，把空间让给映射
        serverList.visibleRowCount = filteredServers.size.coerceIn(1, if (hasChoices) 4 else 8)
        serverScrollPane.preferredSize = null
        if (!hasChoices) {
            mappingListModel.replaceAll(emptyList())
            lastMappingLeadServerId = null
            mappingSectionPanel.revalidate()
            mappingSectionPanel.repaint()
            return
        }

        // 1.0.8：按候选行数动态设置映射列表高度（至少 2 行，父子两条映射同时可见）
        val rows = currentMappingCandidates.size.coerceIn(MAPPING_MIN_ROWS, MAPPING_MAX_ROWS)
        mappingListModel.replaceAll(currentMappingCandidates)
        mappingList.visibleRowCount = rows
        // 1.1.0：映射条目改为三行完整显示（名称 / 本地 / 远程），高度按行数计算，不截断
        mappingScrollPane.preferredSize = JBUI.size(
            JBUI.scale(LIST_WIDTH),
            JBUI.scale(MAPPING_ROW_HEIGHT) * 3 * rows / 2 + JBUI.scale(8)
        )
        mappingScrollPane.horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        // 恢复该服务器此前选过的映射；否则默认选中最深层级（自动匹配项，索引 0）
        val rememberedId = server?.let { mappingChoiceByServerId[it.id] }
        val restoreIndex = rememberedId?.let { id ->
            currentMappingCandidates.indexOfFirst { it.mapping.effectiveId == id }
        } ?: -1
        mappingList.selectedIndex = if (restoreIndex >= 0) restoreIndex else 0

        lastMappingLeadServerId = server?.id
        mappingSectionPanel.revalidate()
        mappingSectionPanel.repaint()
    }

    /**
     * 1.0.8：把当前映射面板中的选择记入 [mappingChoiceByServerId]（归属上一台 lead 服务器）。
     * 面板无候选或未做选择时为 no-op。
     */
    private fun saveCurrentMappingChoice() {
        val previousId = lastMappingLeadServerId ?: return
        val chosen = currentMappingCandidates.getOrNull(mappingList.selectedIndex) ?: return
        mappingChoiceByServerId[previousId] = chosen.mapping.effectiveId
    }

    /**
     * 根据搜索框关键字过滤服务器列表（不区分大小写）
     * 匹配维度：id / 名称 / 主机 / 用户名
     */
    private fun filterList() {
        val keyword = searchField.text.trim().lowercase()

        // 记住过滤前选中服务器的 id，便于过滤后恢复选择
        val previousSelectedId = filteredServers.getOrNull(serverList.selectedIndex)?.id

        val matched = if (keyword.isEmpty()) {
            servers
        } else {
            servers.filter {
                it.id.lowercase().contains(keyword) ||
                    it.name.lowercase().contains(keyword) ||
                    it.host.lowercase().contains(keyword) ||
                    it.user.lowercase().contains(keyword)
            }
        }

        filteredServers = matched
        listModel.replaceAll(matched.map { formatServer(it) })

        // 恢复选择：优先选回之前选中的服务器；否则选第一项
        val restoreIndex = previousSelectedId?.let { id ->
            matched.indexOfFirst { it.id == id }
        } ?: -1
        serverList.selectedIndex = if (restoreIndex >= 0) restoreIndex else 0

        updateCommandOptions()
        updateMappingPanel()
    }

    // 1.1.0：id 与名称相同时只显示一次（不再出现「164 - 164」）
    private fun formatServer(server: ServerConfig): String =
        "${com.alianga.idea.deploy.ui.ServerDisplay.title(server.id, server.name)}  (${server.displayAddress})" +
            if (server.isDefault) "  · " + DeployXBundle.message("dialog.server.select.defaultServer") else ""

    companion object {
        /** 两个列表的首选宽度（px，缩放前） */
        private const val LIST_WIDTH = 520

        /** 服务器列表首选高度：无映射歧义时 */
        private const val SERVER_LIST_HEIGHT_NORMAL = 280

        /** 服务器列表首选高度：映射面板可见时适当压缩，避免弹窗过高（小屏适配） */
        private const val SERVER_LIST_HEIGHT_WITH_MAPPING = 200
    }
}
