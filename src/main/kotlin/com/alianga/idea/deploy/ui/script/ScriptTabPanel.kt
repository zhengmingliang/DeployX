package com.alianga.idea.deploy.ui.script

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptParam
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.service.ScriptManager
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ui.DeployXColors
import com.alianga.idea.deploy.ui.ServerDisplay
import com.alianga.idea.deploy.ui.WrapLayout
import com.alianga.idea.deploy.ui.dialog.ScriptPickerDialog
import com.alianga.idea.deploy.ui.dialog.ScriptRunDialog
import com.alianga.idea.deploy.ui.settings.ScriptEditDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.MessageType
import com.intellij.ui.ColorUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 工具窗口中的脚本库面板（1.1.1 重做）：两行卡片列表 + 行内详情（编辑 / 完成切换、行号、
 * 运行于下拉、参数输入、预览命令切换、运行▾、复制命令、更多）。
 *
 * 保留的原有功能入口：插入上传前 / 后命令、编辑（对话框）、新建（对话框）、预览（对话框）、
 * 复制命令模板、打开设置——都在「更多」菜单下半区与列表右键菜单里。
 */
class ScriptTabPanel(private val project: Project) : JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))) {

    private val scriptManager = ScriptManager.getInstance()
    private val searchField = JBTextField()
    private val groupCombo = JComboBox<String>()
    private val tagCombo = JComboBox<String>()
    private val countLabel = JBLabel()
    private val listModel = DefaultListModel<ScriptConfig>()
    // 第 6 轮：列表宽度始终等于视口宽度（卡片内容在可见宽度内截断），窄宽度下不再出现横向滚动条
    private val list = object : JBList<ScriptConfig>(listModel) {
        override fun getScrollableTracksViewportWidth() = true
    }

    /** 详情区「分组 / 运行于 / 上次运行 / 标签」与参数行共用的标签列 */
    private val kvLabels = mutableListOf<JBLabel>()
    private val paramLabels = mutableListOf<JBLabel>()
    private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    // ===== 详情 =====
    private val nameLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }
    private val nameField = JBTextField()
    private val editToggle = JButton()
    private val groupLabel = JBLabel()
    private val groupField = JBTextField()
    private val runOnCombo = JComboBox<String>()
    private val lastRunLabel = JBLabel()
    private val tagsLabel = JBLabel()
    private val commandHeading = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val previewToggle = JButton()
    private val commandArea = JBTextArea().apply {
        font = JBUI.Fonts.create(Font.MONOSPACED, 12); rows = 6; isEditable = false
    }
    private val lineGutter = JBTextArea().apply {
        font = commandArea.font; isEditable = false; isFocusable = false
        foreground = DeployXColors.secondary; border = JBUI.Borders.empty(0, 4)
    }
    private val paramsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val paramInputs = linkedMapOf<String, JComponent>()
    private val runButton = JButton(AllIcons.Actions.Execute)
    private val runArrow = JButton(AllIcons.General.ArrowDown)
    private val copyButton = JButton(DeployXBundle.message("script.tab2.copyCommand"), AllIcons.Actions.Copy)
    private val moreButton = JButton(DeployXBundle.message("script.tab.button.more"), AllIcons.Actions.More)
    private lateinit var detailPanel: JPanel

    private var editing = false
    /** 正在编辑的脚本 id（切换选中时把编辑内容存回它，而不是新选中的脚本） */
    private var editingId: String? = null
    private var previewing = false
    private var suppressRunOn = false

    private var contextProvider: (() -> ScriptRunContext)? = null
    private var logAppender: ((String?, String) -> Unit)? = null
    private var commandFiller: ((Boolean, String) -> Unit)? = null

    init {
        setupUI()
        refreshAll()
    }

    fun setContextProvider(provider: () -> ScriptRunContext) { contextProvider = provider }
    fun setLogAppender(appender: (String?, String) -> Unit) { logAppender = appender }
    fun setCommandFiller(filler: (preCommand: Boolean, command: String) -> Unit) { commandFiller = filler }

    fun refreshAll() {
        refreshFilters()
        refreshList()
    }

    /** 页签行右侧工具栏：「+ 新建」「重新加载」 */
    fun createTabToolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            object : DumbAwareAction({ DeployXBundle.message("script.tab2.new") }, AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = onNewInline()
            },
            object : DumbAwareAction({ DeployXBundle.message("script.tab2.reload") }, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshAll()
            }
        )
        return ActionManager.getInstance().createActionToolbar("DeployXScriptTab", group, true).also { it.targetComponent = this }
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(8)
        searchField.emptyText.text = DeployXBundle.message("script.tab.search.placeholder")
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshList()
            override fun removeUpdate(e: DocumentEvent?) = refreshList()
            override fun changedUpdate(e: DocumentEvent?) = refreshList()
        })
        groupCombo.addActionListener { refreshList() }
        tagCombo.addActionListener { refreshList() }
        // 第 6 轮：与「操作」页同一窄档阈值（FORM_ROOT / FORM_NARROW_THRESHOLD）：
        // 窄时分组 / 标签筛选换到搜索框下方，搜索框始终占满一行，不再被挤没
        putClientProperty(com.alianga.idea.deploy.ui.AdaptiveRowPanel.FORM_ROOT, true)
        val filters = com.alianga.idea.deploy.ui.AdaptiveRowPanel(
            main = searchField,
            side = JPanel(WrapLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
                add(groupCombo); add(tagCombo); add(countLabel)
            },
            minMainWidth = JBUI.scale(120),
            keepSideLeadingWhenStacked = false,
            sharedThreshold = com.alianga.idea.deploy.ui.AdaptiveRowPanel.FORM_NARROW_THRESHOLD
        )

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = javax.swing.ListCellRenderer<ScriptConfig> { l, s, _, sel, _ -> renderCard(l, s, sel) }
        list.emptyText.text = DeployXBundle.message("script.tab2.empty")
        list.addListSelectionListener { if (!it.valueIsAdjusting) { if (editing) finishEdit(); showDetail() } }
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val idx = list.locationToIndex(e.point)
                if (idx >= 0) list.selectedIndex = idx
                buildMenu().show(list, e.x, e.y)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && SwingUtilities.isLeftMouseButton(e)) onEditDialog()
            }
        })

        detailPanel = buildDetail()
        val split = com.intellij.ui.OnePixelSplitter(true, "DeployX.script.split", 0.45f).apply {
            firstComponent = JBScrollPane(list, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
            // 1.1.1：详情区宽度跟随视口（只纵向滚动），右侧「运行于」下拉与参数提示不再被裁切
            secondComponent = JBScrollPane(com.alianga.idea.deploy.ui.ViewportWidthPanel(BorderLayout()).apply { add(detailPanel, BorderLayout.CENTER) },
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply { border = JBUI.Borders.empty() }
        }
        add(filters, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
    }

    private fun esc(t: String) = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(t)
    private fun hex(c: java.awt.Color) = "#" + ColorUtil.toHex(c)

    private fun serverText(id: String) = if (id.isBlank()) DeployXBundle.message("script.selectAtRuntime")
        else ServerManager.getInstance().getServer(id)?.let { ServerDisplay.title(it.id, it.name) } ?: id

    private fun statusText(s: ScriptConfig): Pair<String, java.awt.Color> = when (s.lastRunStatus) {
        "success" -> DeployXBundle.message("toolwindow.history.status.success") to DeployXColors.success
        "failed" -> DeployXBundle.message("toolwindow.history.status.failed") to DeployXColors.error
        "" -> DeployXBundle.message("script.tab2.notRun") to DeployXColors.secondary
        else -> s.lastRunStatus to DeployXColors.secondary
    }

    private fun renderCard(l: javax.swing.JList<*>, s: ScriptConfig?, sel: Boolean): Component {
        s ?: return JPanel()
        val fg = if (sel) l.selectionForeground else l.foreground
        val chips = s.tags.joinToString(" ") { "<font color='${hex(DeployXColors.link)}'>[${esc(it)}]</font>" }
        val line1 = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("<html><b>${esc(s.name.ifBlank { DeployXBundle.message("script.unnamed") })}</b> $chips</html>").apply { foreground = fg }, BorderLayout.CENTER)
            val (st, c) = statusText(s)
            val last = if (s.lastRunAt > 0) " " + sdf.format(Date(s.lastRunAt)) else ""
            add(JBLabel("<html><font color='${hex(c)}'>${esc(st)}</font>${esc(last)}</html>").apply { foreground = fg }, BorderLayout.EAST)
        }
        val line2 = JBLabel(DeployXBundle.message("script.tab2.meta",
            s.group.ifBlank { DeployXBundle.message("script.defaultGroup") }, serverText(s.serverId),
            ScriptCard.commandLines(s.command), s.params.size, s.runCount)).apply {
            foreground = if (sel) fg else DeployXColors.secondary
        }
        return JPanel(BorderLayout()).apply {
            background = if (sel) l.selectionBackground else l.background
            border = JBUI.Borders.empty(5, 8)
            add(line1, BorderLayout.NORTH); add(line2, BorderLayout.SOUTH)
        }
    }

    private fun buildDetail(): JPanel {
        editToggle.addActionListener { if (editing) finishEdit() else startEdit() }
        previewToggle.addActionListener { previewing = !previewing; updateCommandView() }
        runOnCombo.addActionListener { onRunOnChanged() }
        runButton.addActionListener { onRunPrimary() }
        runArrow.toolTipText = DeployXBundle.message("script.tab2.changeServer")
        runArrow.addActionListener { showServerMenu() }
        copyButton.addActionListener { onCopyCommand() }
        moreButton.addActionListener { buildMenu().show(moreButton, 0, moreButton.height) }
        commandArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateGutter()
            override fun removeUpdate(e: DocumentEvent?) = updateGutter()
            override fun changedUpdate(e: DocumentEvent?) = updateGutter()
        })

        val header = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(JPanel(BorderLayout()).apply { add(nameLabel, BorderLayout.NORTH); add(nameField, BorderLayout.SOUTH) }, BorderLayout.CENTER)
            add(editToggle, BorderLayout.EAST)
        }
        // 第 6 轮：标签 + 值行改用 AdaptiveRowPanel.labeled（窄于统一阈值时标签移到值上方）
        fun kv(key: String, comp: JComponent) = com.alianga.idea.deploy.ui.AdaptiveRowPanel.labeled(
            JBLabel(DeployXBundle.message(key)).apply { foreground = DeployXColors.secondary; kvLabels.add(this) }, comp,
            minFieldWidth = JBUI.scale(80))
        val groupHolder = JPanel(BorderLayout()).apply { add(groupLabel, BorderLayout.NORTH); add(groupField, BorderLayout.SOUTH) }
        val cmdHead = JPanel(BorderLayout()).apply { add(commandHeading, BorderLayout.CENTER); add(previewToggle, BorderLayout.EAST) }
        val cmdScroll = JBScrollPane(commandArea).apply { setRowHeaderView(lineGutter) }
        val buttons = JPanel(WrapLayout(FlowLayout.LEADING, JBUI.scale(4), JBUI.scale(2))).apply {
            add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { add(runButton); add(runArrow) })
            add(copyButton); add(moreButton)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 4)
            listOf<JComponent>(header, kv("script.tab2.k.group", groupHolder), kv("script.tab2.k.runOn", runOnCombo),
                kv("script.tab2.k.lastRun", lastRunLabel), kv("script.tab2.k.tags", tagsLabel),
                paramsPanel, cmdHead, cmdScroll, buttons).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
        }
    }

    private fun updateGutter() { lineGutter.text = ScriptCard.lineNumbers(commandArea.text) }

    private fun selected(): ScriptConfig? = list.selectedValue

    private fun refreshFilters() {
        val allGroups = DeployXBundle.message("script.tab.filter.allGroups")
        val allTags = DeployXBundle.message("script.tab.filter.allTags")
        val g = groupCombo.selectedItem; val t = tagCombo.selectedItem
        groupCombo.removeAllItems(); groupCombo.addItem(allGroups); scriptManager.getGroups().forEach { groupCombo.addItem(it) }
        tagCombo.removeAllItems(); tagCombo.addItem(allTags); scriptManager.getAllTags().forEach { tagCombo.addItem(it) }
        groupCombo.selectedItem = g ?: allGroups; tagCombo.selectedItem = t ?: allTags
    }

    private fun refreshList(selectId: String? = null) {
        val allGroups = DeployXBundle.message("script.tab.filter.allGroups")
        val allTags = DeployXBundle.message("script.tab.filter.allTags")
        val keepId = selectId ?: selected()?.id
        val group = groupCombo.selectedItem?.toString().orEmpty().takeUnless { it == allGroups }.orEmpty()
        val tag = tagCombo.selectedItem?.toString().orEmpty().takeUnless { it == allTags }.orEmpty()
        val scripts = scriptManager.searchScripts(keyword = searchField.text.trim(), group = group, tag = tag)
        listModel.clear(); scripts.forEach { listModel.addElement(it) }
        countLabel.text = DeployXBundle.message("script.tab.count", scripts.size)
        if (scripts.isNotEmpty()) {
            list.selectedIndex = scripts.indexOfFirst { it.id == keepId }.takeIf { it >= 0 } ?: 0
            list.ensureIndexIsVisible(list.selectedIndex)
        }
        showDetail()
    }

    private fun showDetail() {
        val s = selected()
        detailPanel.isVisible = s != null
        if (s == null) return
        nameLabel.text = s.name.ifBlank { DeployXBundle.message("script.unnamed") }
        nameField.text = s.name
        groupLabel.text = s.group.ifBlank { DeployXBundle.message("script.defaultGroup") }
        groupField.text = s.group
        suppressRunOn = true
        runOnCombo.removeAllItems()
        runOnCombo.addItem(DeployXBundle.message("script.selectAtRuntime"))
        val servers = ServerManager.getInstance().getServers()
        servers.forEach { runOnCombo.addItem(ServerDisplay.title(it.id, it.name)) }
        runOnCombo.selectedIndex = servers.indexOfFirst { it.id == s.serverId } + 1
        suppressRunOn = false
        // 1.1.1：「时间 · 服务器 · 状态」，服务器取该脚本最近一条运行历史
        lastRunLabel.text = if (s.lastRunAt > 0) {
            val server = com.alianga.idea.deploy.service.HistoryManager.getInstance().getRecords()
                .filter { it.type == com.alianga.idea.deploy.model.HistoryRecord.OperationType.SCRIPT &&
                    // 新记录按脚本 id；旧记录没有 id，回退按脚本名
                    (if (it.scriptId.isNotBlank()) it.scriptId == s.id else it.sourcePath == s.name) }
                .maxByOrNull { it.timestamp }?.let { ServerDisplay.title(it.serverId, it.serverName) }
            listOfNotNull(sdf.format(Date(s.lastRunAt)), server, statusText(s).first).joinToString(" · ")
        } else DeployXBundle.message("script.tab2.neverRun")
        tagsLabel.text = s.tags.joinToString(" ").ifBlank { "—" }
        buildParams(s)
        previewing = false
        updateEditState()
        updateCommandView()
        updateRunButton(s)
        detailPanel.revalidate(); detailPanel.repaint()
    }

    private fun buildParams(s: ScriptConfig) {
        paramsPanel.removeAll(); paramInputs.clear(); paramLabels.clear()
        if (s.params.isNotEmpty()) {
            s.params.forEach { p ->
                val comp: JComponent = when (p.type) {
                    ScriptParam.ParamType.BOOLEAN -> JBCheckBox(p.description, p.defaultValue.equals("true", true) || p.defaultValue == "1")
                    ScriptParam.ParamType.ENUM -> JComboBox(p.options.toTypedArray()).apply { if (p.defaultValue.isNotBlank()) selectedItem = p.defaultValue }
                    else -> JBTextField(p.defaultValue).apply { emptyText.text = p.description }
                }
                paramInputs[p.name] = comp
                when (comp) {
                    is JBTextField -> comp.document.addDocumentListener(object : DocumentListener {
                        override fun insertUpdate(e: DocumentEvent?) = refreshPreviewIfOn()
                        override fun removeUpdate(e: DocumentEvent?) = refreshPreviewIfOn()
                        override fun changedUpdate(e: DocumentEvent?) = refreshPreviewIfOn()
                    })
                    is JBCheckBox -> comp.addItemListener { refreshPreviewIfOn() }
                    is JComboBox<*> -> comp.addActionListener { refreshPreviewIfOn() }
                }
                // 第 6 轮：参数行与上方「分组 / 运行于」共用标签列，字段左边缘对齐
                paramsPanel.add(com.alianga.idea.deploy.ui.AdaptiveRowPanel.labeled(
                    JBLabel(p.displayLabel + if (p.required) " *" else "").also { paramLabels.add(it) }, comp,
                    minFieldWidth = JBUI.scale(80)).apply { alignmentX = LEFT_ALIGNMENT })
            }
            // 1.1.1：提示按可见宽度换行（标签不换行会被裁切）
            paramsPanel.add(object : JBTextArea(DeployXBundle.message("script.tab2.paramHint", "\${" + DeployXBundle.message("script.tab2.paramName") + "}")) {
                override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().also { it.width = JBUI.scale(100) }
                override fun getMinimumSize(): java.awt.Dimension = java.awt.Dimension(0, super.getMinimumSize().height)
            }.apply {
                isEditable = false; isFocusable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
                border = JBUI.Borders.empty()
                foreground = DeployXColors.secondary; font = JBUI.Fonts.smallFont(); alignmentX = LEFT_ALIGNMENT
            })
        }
        paramsPanel.isVisible = s.params.isNotEmpty()
        alignLabelColumn()
    }


    /** 标签列宽 = 所有标签自然宽度的最大值（上限 140），值 / 参数字段的左边缘因此对齐 */
    private fun alignLabelColumn() {
        val all = kvLabels + paramLabels
        all.forEach { it.preferredSize = null }
        val w = (all.maxOfOrNull { it.preferredSize.width } ?: 0).coerceIn(JBUI.scale(64), JBUI.scale(140))
        all.forEach { it.preferredSize = java.awt.Dimension(w, it.preferredSize.height) }
    }

    private fun collectParams(): Map<String, String> = paramInputs.mapValues { (_, c) ->
        when (c) {
            is JBTextField -> c.text.trim()
            is JBCheckBox -> c.isSelected.toString()
            is JComboBox<*> -> c.selectedItem?.toString().orEmpty()
            else -> ""
        }
    }

    private fun refreshPreviewIfOn() { if (previewing) updateCommandView() }

    private fun runContext(s: ScriptConfig): ScriptRunContext {
        val ctx = context()
        val server = s.serverId.takeIf { it.isNotBlank() }?.let { ServerManager.getInstance().getServer(it) }
        return if (server != null) ctx.copy(server = server) else ctx
    }

    private fun updateCommandView() {
        val s = selected() ?: return
        val has = ScriptCard.hasParams(s) && !editing
        previewToggle.isVisible = has
        if (!has) previewing = false
        previewToggle.text = DeployXBundle.message(if (previewing) "script.tab2.showTemplate" else "script.tab2.previewCommand")
        commandHeading.text = DeployXBundle.message("script.tab2.command") + if (previewing) DeployXBundle.message("script.tab2.substituted") else ""
        if (editing) return
        commandArea.text = if (previewing) try {
            scriptManager.renderCommand(s, collectParams(), runContext(s))
        } catch (e: Exception) { "# " + (e.message ?: "") } else s.command
        commandArea.caretPosition = 0
    }

    private fun updateEditState() {
        nameLabel.isVisible = !editing; nameField.isVisible = editing
        groupLabel.isVisible = !editing; groupField.isVisible = editing
        commandArea.isEditable = editing
        editToggle.text = DeployXBundle.message(if (editing) "script.tab2.done" else "script.tab2.edit")
        editToggle.icon = if (editing) AllIcons.Actions.Checked else AllIcons.Actions.Edit
    }

    private fun startEdit() {
        val s = selected() ?: return
        editing = true
        editingId = s.id
        previewing = false
        commandArea.text = s.command
        updateEditState(); updateCommandView()
        nameField.requestFocusInWindow()
    }

    private fun finishEdit() {
        val s = editingId?.let { scriptManager.getScript(it) }
        val keep = selected()?.id
        editing = false
        editingId = null
        if (s != null) {
            val updated = s.copy(name = nameField.text.trim(), group = groupField.text.trim(), command = commandArea.text)
            if (updated != s) scriptManager.updateScript(s.id, updated)
            refreshFilters()
            SwingUtilities.invokeLater { refreshList(keep) }
        } else updateEditState()
    }

    private fun onRunOnChanged() {
        if (suppressRunOn) return
        val s = selected() ?: return
        val idx = runOnCombo.selectedIndex
        val servers = ServerManager.getInstance().getServers()
        val newId = if (idx <= 0) "" else servers.getOrNull(idx - 1)?.id.orEmpty()
        if (newId == s.serverId) return
        scriptManager.updateScript(s.id, s.copy(serverId = newId))
        refreshList(s.id)
    }

    private fun updateRunButton(s: ScriptConfig) {
        if (ScriptCard.runsDirectly(s)) {
            runButton.text = DeployXBundle.message("script.tab2.run")
            runButton.toolTipText = DeployXBundle.message("script.tab2.runOn", serverText(s.serverId))
        } else {
            runButton.text = DeployXBundle.message("script.tab2.runEllipsis")
            runButton.toolTipText = null
        }
    }

    private fun context(): ScriptRunContext = contextProvider?.invoke() ?: ScriptRunContext.EMPTY

    private fun onRunPrimary() {
        val s = selected() ?: return
        if (editing) finishEdit()
        val cur = selected() ?: return
        if (ScriptCard.runsDirectly(cur)) runDirect(cur, cur.serverId) else onRunDialog()
        if (s.id != cur.id) refreshList(cur.id)
    }

    /** ▾：换一台服务器运行（本次），或打开运行对话框 */
    private fun showServerMenu() {
        val s = selected() ?: return
        val menu = JPopupMenu()
        ServerManager.getInstance().getServers().forEach { sv ->
            menu.add(JMenuItem(DeployXBundle.message("script.tab2.runOn", ServerDisplay.title(sv.id, sv.name))).apply {
                addActionListener { runDirect(s, sv.id) }
            })
        }
        menu.addSeparator()
        menu.add(JMenuItem(DeployXBundle.message("script.tab2.runEllipsis")).apply { addActionListener { onRunDialog() } })
        menu.show(runArrow, 0, runArrow.height)
    }

    /** 直接在指定服务器上运行（参数取页面上的输入），日志写入日志页 */
    private fun runDirect(s: ScriptConfig, serverId: String) {
        val params = collectParams()
        val ctx = context()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, DeployXBundle.message("script.tab2.running", s.name), true) {
            private val cancelToken = com.alianga.idea.deploy.service.DeployCancelToken()

            override fun onCancel() {
                cancelToken.cancel()
            }

            override fun run(indicator: ProgressIndicator) {
                try {
                    scriptManager.runScript(s, params, ctx, serverId,
                        logCallback = { line -> logAppender?.invoke(serverId, line) },
                        confirmCallback = { cmd ->
                            var ok = false
                            SwingUtilities.invokeAndWait {
                                ok = Messages.showYesNoDialog(project, DeployXBundle.message("script.tab2.dangerConfirm", cmd),
                                    DeployXBundle.message("script.tab2.run"), Messages.getWarningIcon()) == Messages.YES
                            }
                            ok
                        },
                        cancelToken = cancelToken)
                } catch (e: Exception) {
                    logAppender?.invoke(serverId, e.message ?: e.toString())
                }
            }
            override fun onFinished() = refreshList(s.id)
        })
    }

    private fun onRunDialog() {
        val script = selected() ?: return
        ScriptRunDialog(project, script, context()) { line ->
            val serverId = script.serverId.ifBlank { context().server?.id ?: context().mapping?.serverId ?: "" }
            logAppender?.invoke(serverId, line)
        }.show()
        refreshAll()
    }

    private fun toast(text: String, anchor: JComponent) {
        JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(esc(text), MessageType.INFO, null)
            .setFadeoutTime(2000).createBalloon()
            .show(RelativePoint.getCenterOf(anchor), Balloon.Position.above)
    }

    private fun onCopyCommand() {
        selected() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(commandArea.text))
        toast(DeployXBundle.message("script.tab2.copied"), copyButton)
    }

    private fun buildMenu(): JPopupMenu {
        val menu = JPopupMenu()
        fun item(key: String, icon: javax.swing.Icon?, action: () -> Unit) =
            menu.add(JMenuItem(DeployXBundle.message(key), icon).apply { addActionListener { action() } })
        // 1.1.1：右键 / 更多菜单也能运行
        selected()?.let { s ->
            if (ScriptCard.runsDirectly(s)) item("script.tab2.run", AllIcons.Actions.Execute) { runDirect(s, s.serverId) }
                .toolTipText = DeployXBundle.message("script.tab2.runOn", serverText(s.serverId))
            item("script.tab2.runEllipsis", AllIcons.Actions.Execute) { onRunDialog() }
            menu.addSeparator()
        }
        item("script.tab2.duplicate", AllIcons.Actions.Copy) { onDuplicate() }
        item("script.tab2.exportJson", AllIcons.ToolbarDecorator.Export) { onExportJson() }
        menu.addSeparator()
        item("script.tab.menu.deleteScript", AllIcons.Actions.GC) { onDelete() }.foreground = DeployXColors.error
        menu.addSeparator()
        // 原有功能入口（保留）
        item("script.tab.menu.insertPreCommand", AllIcons.Actions.Edit) { onFill(true) }
        item("script.tab.menu.insertPostCommand", AllIcons.Actions.Edit) { onFill(false) }
        item("script.tab.menu.editScript", AllIcons.Actions.Edit) { onEditDialog() }
        item("script.tab.menu.newScript", AllIcons.General.Add) { onCreateDialog() }
        item("script.tab.button.preview", AllIcons.Actions.Preview) { onDryRun() }
        item("script.tab.menu.copyCommand", AllIcons.Actions.Copy) { onCopyTemplate() }
        menu.addSeparator()
        item("script.tab.menu.openSettings", AllIcons.General.Settings) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX")
        }
        return menu
    }

    /** 「+ 新建」：行内插入「新脚本」并进入编辑，清空搜索与过滤 */
    private fun onNewInline() {
        if (editing) finishEdit()
        val created = scriptManager.addScript(ScriptConfig(name = DeployXBundle.message("script.tab2.newName"), command = ""))
        searchField.text = ""
        refreshFilters()
        groupCombo.selectedIndex = 0; tagCombo.selectedIndex = 0
        refreshList(created.id)
        startEdit()
    }

    private fun onDuplicate() {
        val s = selected() ?: return
        val copy = scriptManager.addScript(ScriptCard.duplicate(s, ScriptConfig.generateId(),
            DeployXBundle.message("script.tab2.copySuffix"), System.currentTimeMillis()))
        refreshFilters()
        refreshList(copy.id)
    }

    private fun onExportJson() {
        val s = selected() ?: return
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(DeployXBundle.message("script.tab2.exportJson"), "", "json")
        val wrapper = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save("deployx-script-${s.name.ifBlank { s.id }}.json") ?: return
        val json = com.alianga.idea.deploy.model.NullSafeModels.builder().setPrettyPrinting().disableHtmlEscaping().create().toJson(s)
        wrapper.file.writeText(json)
        toast(DeployXBundle.message("settings.script.exported", wrapper.file.absolutePath), moreButton)
    }

    private fun onDryRun() {
        val script = selected() ?: return
        val dialog = ScriptPickerDialog(project, context(), initialScriptId = script.id)
        if (dialog.showAndGet()) {
            CopyPasteManager.getInstance().setContents(StringSelection(dialog.getResultCommand()))
            Messages.showInfoMessage(DeployXBundle.message("script.tab.preview.generated"), DeployXBundle.message("script.tab.preview.title"))
        }
    }

    private fun onCopyTemplate() {
        val script = selected() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(script.command))
        toast(DeployXBundle.message("script.tab.copy.success"), moreButton)
    }

    private fun onFill(pre: Boolean) {
        val script = selected()
        val dialog = ScriptPickerDialog(project, context(), initialScriptId = script?.id)
        if (dialog.showAndGet()) {
            commandFiller?.invoke(pre, dialog.getResultCommand())
            logAppender?.invoke(null, DeployXBundle.message(if (pre) "script.tab.inserted.preCommand" else "script.tab.inserted.postCommand"))
        }
    }

    private fun onCreateDialog() {
        val dialog = ScriptEditDialog(null)
        if (dialog.showAndGet()) {
            val c = scriptManager.addScript(dialog.getScriptConfig())
            refreshFilters(); refreshList(c.id)
        }
    }

    private fun onEditDialog() {
        val script = selected() ?: return
        if (editing) finishEdit()
        val dialog = ScriptEditDialog(selected() ?: script)
        if (dialog.showAndGet()) {
            scriptManager.updateScript(script.id, dialog.getScriptConfig())
            refreshAll()
        }
    }

    private fun onDelete() {
        val script = selected() ?: return
        val result = Messages.showYesNoDialog(
            DeployXBundle.message("settings.script.confirm.delete", script.name),
            DeployXBundle.message("settings.script.confirm.delete.title"),
            DeployXBundle.message("settings.script.confirm.delete.yes"),
            DeployXBundle.message("common.cancel"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            editing = false; editingId = null
            scriptManager.deleteScript(script.id)
            refreshAll()
        }
    }

    /** 语言切换后刷新文案 */
    fun relocalize() {
        searchField.emptyText.text = DeployXBundle.message("script.tab.search.placeholder")
        list.emptyText.text = DeployXBundle.message("script.tab2.empty")
        copyButton.text = DeployXBundle.message("script.tab2.copyCommand")
        moreButton.text = DeployXBundle.message("script.tab.button.more")
        refreshAll()
        revalidate(); repaint()
    }
}
