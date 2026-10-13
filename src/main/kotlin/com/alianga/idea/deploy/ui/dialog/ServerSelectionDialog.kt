package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.DeployXColors
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * 服务器选择对话框（1.1.1 重做：服务器—映射一体列表）。
 *
 * - 每台服务器一行复选框「名称 · id X」+ 主机 +「默认服务器」标签；
 * - 勾选后在其下方展开该服务器自己的映射单选组（名称、「默认·最深层级」、本地 / 远程）；
 * - 副标题「已选择 N 个目录 · 路径」；部署模式底部显示流程说明；
 * - 无可用服务器时在对话框内显示空状态（说明 + 路径 +「添加目录映射…」），不再弹通知；
 * - 搜索框常驻；推送模式「同时执行上传前 / 后命令」按每台选中服务器自己的映射启用。
 *
 * 对外结果字段（selectedServers / selectedMappingIdsByServerId / executePre/PostCommand 等）与旧版保持一致。
 */
class ServerSelectionDialog(
    private val servers: List<ServerConfig>,
    private val titleText: String = DeployXBundle.message("dialog.server.select.title"),
    private val messageText: String = DeployXBundle.message("dialog.server.select.message"),
    private val showCommandOptions: Boolean = false,
    private val commandAvailabilityByServerId: Map<String, CommandAvailability> = emptyMap(),
    /** key = serverId，value = 该服务器上选中文件匹配到的全部映射（含父子嵌套） */
    private val resolvedByServerId: Map<String, List<MappingManager.ResolvedMapping>> = emptyMap(),
    /** 确认按钮的具体动词（部署 / 推送 / 拉取 / 预览） */
    private val okText: String? = null,
    /** 因没有匹配映射而被隐藏的服务器数 */
    private val hiddenServerCount: Int = 0,
    /** 1.1.1：选中的本地路径（副标题与空状态展示） */
    private val selectedPaths: List<String> = emptyList(),
    /** 1.1.1：部署模式，底部显示流程说明 */
    private val deployMode: Boolean = false,
    /** 1.1.1：空状态说明（servers 为空时显示） */
    private val emptyReason: String? = null
) : DialogWrapper(null) {

    data class CommandAvailability(
        val hasPreCommand: Boolean = false,
        val hasPostCommand: Boolean = false
    )

    private inner class Row(val server: ServerConfig) {
        val candidates = MappingManager.sortByPriority(resolvedByServerId[server.id].orEmpty())
        val check = JBCheckBox("<html><b>" + esc(server.name.ifBlank { server.id }) + "</b> · id " + esc(server.id) + "</html>")
        val radios = candidates.mapIndexed { i, r ->
            JBRadioButton(mappingText(r, i == 0)).apply { isSelected = i == 0 }
        }
        val mappingPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyLeft(28)
            isOpaque = false
            val g = ButtonGroup()
            radios.forEach { g.add(it); it.alignmentX = Component.LEFT_ALIGNMENT; add(it) }
            isVisible = false
        }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            val head = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(check)
                add(JBLabel(server.displayAddress).apply { foreground = DeployXColors.secondary })
                if (server.isDefault) add(JBLabel(DeployXBundle.message("dialog.server.select.defaultServer")).apply {
                    foreground = DeployXColors.link; font = JBUI.Fonts.smallFont()
                })
            }
            head.alignmentX = Component.LEFT_ALIGNMENT; mappingPanel.alignmentX = Component.LEFT_ALIGNMENT
            add(head); add(mappingPanel)
        }

        init {
            check.addItemListener { mappingPanel.isVisible = check.isSelected && candidates.isNotEmpty(); onSelectionChanged() }
            radios.forEach { it.addItemListener { updateCommandOptions() } }
        }

        fun chosen(): MappingManager.ResolvedMapping? = candidates.getOrNull(radios.indexOfFirst { it.isSelected }) ?: candidates.firstOrNull()

        fun matches(q: String) = q.isEmpty() || listOf(server.id, server.name, server.host, server.user).any { it.lowercase().contains(q) }
    }

    private val rows = servers.map { Row(it) }
    private val listPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val noMatchLabel = JBLabel(DeployXBundle.message("dialog.server.select.noMatch")).apply {
        foreground = DeployXColors.secondary; border = JBUI.Borders.empty(8); alignmentX = Component.LEFT_ALIGNMENT
    }
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = DeployXBundle.message("dialog.server.select.search.placeholder")
    }
    private val selectionCountLabel = JBLabel().apply { foreground = DeployXColors.secondary }
    private val executePreCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePreCommand"), false)
    private val executePostCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePostCommand"), false)
    private var keyDispatcher: KeyEventDispatcher? = null

    var selectedServer: ServerConfig? = null
        private set
    var selectedServers: List<ServerConfig> = emptyList()
        private set
    var selectedMappingIdsByServerId: Map<String, String> = emptyMap()
        private set
    var selectedMappingId: String? = null
        private set
    var executePreCommand: Boolean = false
        private set
    var executePostCommand: Boolean = false
        private set

    init {
        title = titleText
        okText?.let { setOKButtonText(it) }
        // 默认勾选默认服务器（没有则第一台）
        (rows.firstOrNull { it.server.isDefault } ?: rows.firstOrNull())?.check?.isSelected = true
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = filterList()
        })
        // 搜索框 Enter：仅一个匹配时勾选并确认
        searchField.textEditor.addActionListener {
            val visible = rows.filter { it.panel.isVisible }
            if (visible.size == 1) { visible[0].check.isSelected = true; doOKAction() }
        }
        filterList()
        init()
        if (servers.isEmpty()) isOKActionEnabled = false
        initValidation()
        onSelectionChanged()
    }

    private fun esc(t: String) = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(t)

    private fun mappingText(r: MappingManager.ResolvedMapping, deepest: Boolean): String {
        val marker = if (deepest) " <font color='#${com.intellij.ui.ColorUtil.toHex(DeployXColors.secondary)}'>" +
            esc(DeployXBundle.message("dialog.server.select.mapping.defaultDeepest")) + "</font>" else ""
        return "<html><b>" + esc(r.mapping.name.ifBlank { r.mapping.localDir }) + "</b>$marker<br>" +
            "<code>" + esc(r.mapping.localDir) + " → " + esc(r.mapping.remoteDir) + "</code></html>"
    }

    private fun checkedRows() = rows.filter { it.check.isSelected && it.panel.isVisible }

    private fun onSelectionChanged() {
        selectionCountLabel.text = DeployXBundle.message("dialog.server.select.selectedCount", checkedRows().size)
        updateCommandOptions()
        listPanel.revalidate(); listPanel.repaint()
    }

    /** 按每台选中服务器自己（所选）映射决定命令选项是否可用 */
    private fun updateCommandOptions() {
        if (!showCommandOptions) return
        var pre = false; var post = false
        checkedRows().forEach { row ->
            val m = row.chosen()?.mapping
            if (m != null) {
                pre = pre || (m.effectivePreCommandEnabled && m.preCommand.isNotBlank())
                post = post || (m.effectivePostCommandEnabled && m.postCommand.isNotBlank())
            } else {
                val a = commandAvailabilityByServerId[row.server.id] ?: CommandAvailability()
                pre = pre || a.hasPreCommand; post = post || a.hasPostCommand
            }
        }
        executePreCommandCheck.isEnabled = pre
        executePostCommandCheck.isEnabled = post
        if (!pre) executePreCommandCheck.isSelected = false
        if (!post) executePostCommandCheck.isSelected = false
    }

    private fun filterList() {
        val q = searchField.text.trim().lowercase()
        listPanel.removeAll()
        rows.forEach { it.panel.isVisible = it.matches(q); if (it.panel.isVisible) listPanel.add(it.panel) }
        if (rows.none { it.panel.isVisible } && rows.isNotEmpty()) listPanel.add(noMatchLabel)
        onSelectionChanged()
    }

    override fun doValidate(): ValidationInfo? =
        if (servers.isNotEmpty() && checkedRows().isEmpty())
            ValidationInfo(DeployXBundle.message("dialog.server.select.atLeastOne"), listPanel)
        else null

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(6))).apply { minimumSize = JBUI.size(480, 200) }
        val head = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel(messageText))
            subtitle()?.let { add(JBLabel(it).apply { foreground = DeployXColors.secondary }) }
            components.forEach { (it as JComponent).alignmentX = Component.LEFT_ALIGNMENT }
        }
        root.add(head, BorderLayout.NORTH)
        if (servers.isEmpty()) {
            root.add(emptyState(), BorderLayout.CENTER)
            return root
        }
        val center = JPanel(BorderLayout(0, JBUI.scale(4)))
        center.add(searchField, BorderLayout.NORTH)
        val holder = JPanel(BorderLayout()).apply { add(listPanel, BorderLayout.NORTH) }
        center.add(JBScrollPane(holder).apply {
            preferredSize = JBUI.size(540, (servers.size * 36 + 20).coerceIn(80, 320))
            border = JBUI.Borders.customLine(DeployXColors.border, 1)
        }, BorderLayout.CENTER)
        root.add(center, BorderLayout.CENTER)

        val south = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        if (hiddenServerCount > 0) {
            south.add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
                add(JBLabel(DeployXBundle.message("dialog.server.select.hiddenCount", hiddenServerCount)).apply {
                    foreground = DeployXColors.secondary; font = JBUI.Fonts.smallFont(); border = JBUI.Borders.emptyRight(8)
                })
                add(addMappingLink(DeployXBundle.message("dialog.server.select.addMapping")))
            })
        }
        if (showCommandOptions) { south.add(executePreCommandCheck); south.add(executePostCommandCheck) }
        if (deployMode) south.add(JBLabel(DeployXBundle.message("dialog.server.select.flowNote")).apply {
            foreground = DeployXColors.secondary; font = JBUI.Fonts.smallFont()
        })
        south.add(selectionCountLabel)
        south.components.forEach { (it as JComponent).alignmentX = Component.LEFT_ALIGNMENT }
        root.add(south, BorderLayout.SOUTH)

        registerKeyRouting()
        SwingUtilities.invokeLater { searchField.textEditor.requestFocusInWindow() }
        return root
    }

    private fun subtitle(): String? {
        if (selectedPaths.isEmpty()) return null
        val first = selectedPaths.first()
        return DeployXBundle.message("dialog.server.select.subtitle", selectedPaths.size, first) +
            if (selectedPaths.size > 1) " …" else ""
    }

    private fun addMappingLink(text: String) = ActionLink(text) {
        close(CANCEL_EXIT_CODE)
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(null as com.intellij.openapi.project.Project?, "DeployX")
    }

    private fun emptyState(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16, 8)
        add(JBLabel(emptyReason ?: DeployXBundle.message("dialog.server.select.empty")).apply { setAllowAutoWrapping(true) })
        selectedPaths.take(3).forEach { add(JBLabel(it).apply { foreground = DeployXColors.secondary }) }
        add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
        add(addMappingLink(DeployXBundle.message("dialog.server.select.addDirMapping")))
        components.forEach { (it as JComponent).alignmentX = Component.LEFT_ALIGNMENT }
    }

    /** 对话框可见时，按字母 / 数字键自动把焦点路由到搜索框 */
    private fun registerKeyRouting() {
        keyDispatcher?.let { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) }
        keyDispatcher = KeyEventDispatcher { e ->
            if (e.id != KeyEvent.KEY_TYPED || !isShowing) return@KeyEventDispatcher false
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner is JTextComponent) return@KeyEventDispatcher false
            val c = e.keyChar
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                val editor = searchField.textEditor
                editor.requestFocusInWindow()
                editor.text += c.toString()
                editor.caretPosition = editor.text.length
                return@KeyEventDispatcher true
            }
            false
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
    }

    override fun dispose() {
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            keyDispatcher = null
        }
        super.dispose()
    }

    override fun doOKAction() {
        val checked = checkedRows()
        selectedServers = checked.map { it.server }
        selectedServer = selectedServers.firstOrNull()
        executePreCommand = showCommandOptions && executePreCommandCheck.isEnabled && executePreCommandCheck.isSelected
        executePostCommand = showCommandOptions && executePostCommandCheck.isEnabled && executePostCommandCheck.isSelected
        selectedMappingIdsByServerId = checked.mapNotNull { row -> row.chosen()?.let { row.server.id to it.mapping.effectiveId } }.toMap()
        selectedMappingId = selectedMappingIdsByServerId.values.firstOrNull()
        super.doOKAction()
    }
}
