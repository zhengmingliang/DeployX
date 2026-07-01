package com.alianga.idea.deploy.ui.script

import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.service.ScriptManager
import com.alianga.idea.deploy.ui.dialog.ScriptPickerDialog
import com.alianga.idea.deploy.ui.dialog.ScriptRunDialog
import com.alianga.idea.deploy.ui.settings.ScriptEditDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * 工具窗口中的脚本库面板。
 */
class ScriptTabPanel(private val project: Project) : JPanel(BorderLayout(8, 8)) {

    private val scriptManager = ScriptManager.getInstance()
    private val searchField = JBTextField()
    private val groupCombo = JComboBox<String>()
    private val tagCombo = JComboBox<String>()
    private val countLabel = JBLabel()
    private val tableModel = ScriptTableModel()
    private val table = JBTable(tableModel)
    private val previewTitle = JBLabel("请选择脚本")
    private val previewArea = JBTextArea(8, 80)

    private var contextProvider: (() -> ScriptRunContext)? = null
    private var logAppender: ((String?, String) -> Unit)? = null
    private var commandFiller: ((Boolean, String) -> Unit)? = null

    init {
        setupUI()
        refreshAll()
    }

    fun setContextProvider(provider: () -> ScriptRunContext) {
        contextProvider = provider
    }

    fun setLogAppender(appender: (String?, String) -> Unit) {
        logAppender = appender
    }

    fun setCommandFiller(filler: (preCommand: Boolean, command: String) -> Unit) {
        commandFiller = filler
    }

    fun refreshAll() {
        refreshFilters()
        refreshTable()
    }

    private fun setupUI() {
        border = JBUI.Borders.empty(8)
        setupFilters()
        setupTable()
        setupPreviewArea()

        val topPanel = JPanel(BorderLayout(8, 0)).apply {
            add(createFilterPanel(), BorderLayout.CENTER)
            add(createToolbar(), BorderLayout.EAST)
        }
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, createTablePanel(), createPreviewPanel()).apply {
            dividerLocation = 360
            resizeWeight = 0.74
            border = JBUI.Borders.emptyTop(8)
        }

        add(topPanel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
    }

    private fun setupFilters() {
        searchField.emptyText.text = "搜索名称、描述、标签或命令"
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshTable()
            override fun removeUpdate(e: DocumentEvent?) = refreshTable()
            override fun changedUpdate(e: DocumentEvent?) = refreshTable()
        })
        groupCombo.addActionListener { refreshTable() }
        tagCombo.addActionListener { refreshTable() }
    }

    private fun createFilterPanel(): JComponent {
        return JPanel(BorderLayout(6, 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(groupCombo)
                add(tagCombo)
                add(JButton(AllIcons.Actions.Refresh).apply {
                    toolTipText = "刷新脚本库"
                    isFocusable = false
                    addActionListener { refreshAll() }
                })
                add(countLabel)
            }, BorderLayout.EAST)
        }
    }

    private fun createToolbar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(JButton("运行", AllIcons.Actions.Execute).apply { addActionListener { onRun() } })
            add(JButton("预览", AllIcons.Actions.Preview).apply { addActionListener { onDryRun() } })
            add(JButton("更多", AllIcons.Actions.More).apply { addActionListener { showMoreMenu(this) } })
        }
    }

    private fun setupTable() {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoCreateRowSorter = true
        table.emptyText.text = "暂无脚本"
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showSelectedPreview()
        }
        table.columnModel.getColumn(0).preferredWidth = 180
        table.columnModel.getColumn(1).preferredWidth = 90
        table.columnModel.getColumn(2).preferredWidth = 130
        table.columnModel.getColumn(3).preferredWidth = 60
        table.columnModel.getColumn(4).preferredWidth = 150
        table.columnModel.getColumn(5).preferredWidth = 80
        table.columnModel.getColumn(6).preferredWidth = 120
        table.columnModel.getColumn(7).preferredWidth = 90
    }

    private fun createTablePanel(): JComponent {
        return JBScrollPane(table).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun setupPreviewArea() {
        previewArea.isEditable = false
        previewArea.font = Font("Monospaced", Font.PLAIN, 12)
        previewArea.lineWrap = true
        previewArea.wrapStyleWord = true
        previewArea.border = JBUI.Borders.empty(4)
        previewTitle.font = previewTitle.font.deriveFont(Font.BOLD)
    }

    private fun createPreviewPanel(): JComponent {
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(previewTitle, BorderLayout.WEST)
            add(JButton("复制模板", AllIcons.Actions.Copy).apply { addActionListener { onCopy() } }, BorderLayout.EAST)
        }
        return JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(previewArea).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
    }

    private fun showMoreMenu(invoker: JComponent) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("复制命令模板", AllIcons.Actions.Copy).apply { addActionListener { onCopy() } })
        menu.add(JMenuItem("插入到上传前命令", AllIcons.Actions.Edit).apply { addActionListener { onFill(true) } })
        menu.add(JMenuItem("插入到上传后命令", AllIcons.Actions.Edit).apply { addActionListener { onFill(false) } })
        menu.addSeparator()
        menu.add(JMenuItem("新建脚本", AllIcons.General.Add).apply { addActionListener { onCreate() } })
        menu.add(JMenuItem("编辑脚本", AllIcons.Actions.Edit).apply { addActionListener { onEdit() } })
        menu.add(JMenuItem("复制脚本", AllIcons.Actions.Copy).apply { addActionListener { onDuplicate() } })
        menu.add(JMenuItem("删除脚本", AllIcons.Actions.DeleteTag).apply { addActionListener { onDelete() } })
        menu.addSeparator()
        menu.add(JMenuItem("打开脚本库设置", AllIcons.General.Settings).apply {
            addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX") }
        })
        menu.show(invoker, 0, invoker.height)
    }

    private fun refreshFilters() {
        val selectedGroup = groupCombo.selectedItem?.toString() ?: "全部分组"
        val selectedTag = tagCombo.selectedItem?.toString() ?: "全部标签"
        groupCombo.removeAllItems()
        groupCombo.addItem("全部分组")
        scriptManager.getGroups().forEach { groupCombo.addItem(it) }
        tagCombo.removeAllItems()
        tagCombo.addItem("全部标签")
        scriptManager.getAllTags().forEach { tagCombo.addItem(it) }
        groupCombo.selectedItem = selectedGroup
        tagCombo.selectedItem = selectedTag
    }

    private fun refreshTable() {
        val selectedId = selectedScript()?.id
        val group = groupCombo.selectedItem?.toString().orEmpty().takeUnless { it == "全部分组" }.orEmpty()
        val tag = tagCombo.selectedItem?.toString().orEmpty().takeUnless { it == "全部标签" }.orEmpty()
        val scripts = scriptManager.searchScripts(
            keyword = searchField.text.trim(),
            group = group,
            tag = tag
        )
        tableModel.setData(scripts)
        countLabel.text = "${scripts.size} 个脚本"

        if (scripts.isEmpty()) {
            clearPreview()
            return
        }
        val modelIndex = scripts.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
        val viewIndex = table.convertRowIndexToView(modelIndex)
        if (viewIndex >= 0) {
            table.selectionModel.setSelectionInterval(viewIndex, viewIndex)
            table.scrollRectToVisible(table.getCellRect(viewIndex, 0, true))
        }
    }

    private fun selectedScript(): ScriptConfig? {
        val row = table.selectedRow
        if (row < 0) return null
        return tableModel.getScriptAt(table.convertRowIndexToModel(row))
    }

    private fun showSelectedPreview() {
        val script = selectedScript() ?: return clearPreview()
        previewTitle.text = "命令预览 - ${script.name.ifBlank { "未命名脚本" }}"
        previewArea.text = script.command
    }

    private fun clearPreview() {
        previewTitle.text = "暂无脚本"
        previewArea.text = ""
    }

    private fun context(): ScriptRunContext = contextProvider?.invoke() ?: ScriptRunContext.EMPTY

    private fun onRun() {
        val script = selectedScript() ?: return
        ScriptRunDialog(project, script, context()) { line ->
            val serverId = script.serverId.ifBlank { context().server?.id ?: context().mapping?.serverId ?: "" }
            logAppender?.invoke(serverId, line)
        }.show()
        refreshAll()
    }

    private fun onDryRun() {
        val script = selectedScript() ?: return
        val dialog = ScriptPickerDialog(project, context(), initialScriptId = script.id)
        if (dialog.showAndGet()) {
            val command = dialog.getResultCommand()
            CopyPasteManager.getInstance().setContents(StringSelection(command))
            Messages.showInfoMessage("命令已生成并复制到剪贴板", "预览脚本")
        }
    }

    private fun onCopy() {
        val script = selectedScript() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(script.command))
        Messages.showInfoMessage("脚本模板已复制", "复制命令")
    }

    private fun onFill(pre: Boolean) {
        val script = selectedScript()
        val dialog = ScriptPickerDialog(project, context(), initialScriptId = script?.id)
        if (dialog.showAndGet()) {
            commandFiller?.invoke(pre, dialog.getResultCommand())
            logAppender?.invoke(null, "已插入${if (pre) "上传前" else "上传后"}命令")
        }
    }

    private fun onCreate() {
        val dialog = ScriptEditDialog(null)
        if (dialog.showAndGet()) {
            scriptManager.addScript(dialog.getScriptConfig())
            refreshAll()
        }
    }

    private fun onEdit() {
        val script = selectedScript() ?: return
        val dialog = ScriptEditDialog(script)
        if (dialog.showAndGet()) {
            scriptManager.updateScript(script.id, dialog.getScriptConfig())
            refreshAll()
        }
    }

    private fun onDuplicate() {
        val script = selectedScript() ?: return
        scriptManager.copyScript(script.id)
        refreshAll()
    }

    private fun onDelete() {
        val script = selectedScript() ?: return
        val result = Messages.showYesNoDialog("确定要删除脚本 '${script.name}' 吗？", "删除脚本", "删除", "取消", Messages.getQuestionIcon())
        if (result == Messages.YES) {
            scriptManager.deleteScript(script.id)
            refreshAll()
        }
    }

    private class ScriptTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Group", "Server", "Params", "Tags", "Run Count", "Last Run", "Status")
        private var scripts = listOf<ScriptConfig>()
        private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun setData(data: List<ScriptConfig>) {
            scripts = data
            fireTableDataChanged()
        }

        fun getScriptAt(row: Int): ScriptConfig? = scripts.getOrNull(row)

        override fun getRowCount(): Int = scripts.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val script = scripts[rowIndex]
            return when (columnIndex) {
                0 -> script.name.ifBlank { "未命名脚本" }
                1 -> script.group.ifBlank { "默认" }
                2 -> script.serverId.ifBlank { "运行时选择" }
                3 -> script.params.size
                4 -> script.tags.joinToString(", ")
                5 -> script.runCount
                6 -> if (script.lastRunAt > 0) sdf.format(Date(script.lastRunAt)) else ""
                7 -> script.lastRunStatus.ifBlank { "未运行" }
                else -> ""
            }
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                3, 5 -> Int::class.javaObjectType
                else -> String::class.java
            }
        }
    }
}
