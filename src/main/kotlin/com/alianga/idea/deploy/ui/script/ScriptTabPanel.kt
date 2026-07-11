package com.alianga.idea.deploy.ui.script

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.service.ScriptManager
import com.alianga.idea.deploy.ui.ScriptEditorFactory
import com.alianga.idea.deploy.ui.dialog.ScriptPickerDialog
import com.alianga.idea.deploy.ui.dialog.ScriptRunDialog
import com.alianga.idea.deploy.ui.settings.ScriptEditDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.EditorTextField
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
    private val previewTitle = JBLabel(DeployXBundle.message("script.tab.preview.selectFirst"))
    private val previewArea: EditorTextField = ScriptEditorFactory.createViewer("", project)

    // 按钮引用（保留以便语言切换时刷新文案）
    private val refreshFilterButton = JButton(AllIcons.Actions.Refresh)
    private val runButton = JButton(DeployXBundle.message("script.tab.button.run"), AllIcons.Actions.Execute)
    private val previewButton = JButton(DeployXBundle.message("script.tab.button.preview"), AllIcons.Actions.Preview)
    private val moreButton = JButton(DeployXBundle.message("script.tab.button.more"), AllIcons.Actions.More)
    private val copyTemplateButton = JButton(DeployXBundle.message("script.tab.button.copyTemplate"), AllIcons.Actions.Copy)

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
        searchField.emptyText.text = DeployXBundle.message("script.tab.search.placeholder")
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
                add(refreshFilterButton.apply {
                    isFocusable = false
                    addActionListener { refreshAll() }
                })
                add(countLabel)
            }, BorderLayout.EAST)
        }
    }

    private fun createToolbar(): JComponent {
        return JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(runButton.apply { addActionListener { onRun() } })
            add(previewButton.apply { addActionListener { onDryRun() } })
            add(moreButton.apply { addActionListener { showMoreMenu(this) } })
        }
    }

    private fun setupTable() {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoCreateRowSorter = true
        table.emptyText.text = DeployXBundle.message("script.tab.noScripts")
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
        previewTitle.font = previewTitle.font.deriveFont(Font.BOLD)
    }

    private fun createPreviewPanel(): JComponent {
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(previewTitle, BorderLayout.WEST)
            add(copyTemplateButton.apply { addActionListener { onCopy() } }, BorderLayout.EAST)
        }
        return JPanel(BorderLayout(4, 4)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(header, BorderLayout.NORTH)
            add(previewArea, BorderLayout.CENTER)
        }
    }

    /**
     * 语言切换后刷新所有已构建组件的本地化文案。在 EDT 上由父面板触发。
     */
    fun relocalize() {
        searchField.emptyText.text = DeployXBundle.message("script.tab.search.placeholder")
        refreshFilterButton.toolTipText = DeployXBundle.message("script.tab.refresh.tooltip")
        runButton.text = DeployXBundle.message("script.tab.button.run")
        previewButton.text = DeployXBundle.message("script.tab.button.preview")
        moreButton.text = DeployXBundle.message("script.tab.button.more")
        copyTemplateButton.text = DeployXBundle.message("script.tab.button.copyTemplate")
        table.emptyText.text = DeployXBundle.message("script.tab.noScripts")
        // 表格列名刷新并重绘
        tableModel.relocalizeColumns()
        // 过滤器下拉项（"全部分组/全部标签"）刷新
        refreshFilters()
        // 预览标题按当前选中状态刷新
        if (selectedScript() == null) clearPreview() else showSelectedPreview()
        revalidate()
        repaint()
    }

    private fun showMoreMenu(invoker: JComponent) {
        val menu = JPopupMenu()
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.copyCommand"), AllIcons.Actions.Copy).apply { addActionListener { onCopy() } })
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.insertPreCommand"), AllIcons.Actions.Edit).apply { addActionListener { onFill(true) } })
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.insertPostCommand"), AllIcons.Actions.Edit).apply { addActionListener { onFill(false) } })
        menu.addSeparator()
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.newScript"), AllIcons.General.Add).apply { addActionListener { onCreate() } })
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.editScript"), AllIcons.Actions.Edit).apply { addActionListener { onEdit() } })
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.copyScript"), AllIcons.Actions.Copy).apply { addActionListener { onDuplicate() } })
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.deleteScript"), AllIcons.Actions.DeleteTag).apply { addActionListener { onDelete() } })
        menu.addSeparator()
        menu.add(JMenuItem(DeployXBundle.message("script.tab.menu.openSettings"), AllIcons.General.Settings).apply {
            addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX") }
        })
        menu.show(invoker, 0, invoker.height)
    }

    private fun refreshFilters() {
        val allGroups = DeployXBundle.message("script.tab.filter.allGroups")
        val allTags = DeployXBundle.message("script.tab.filter.allTags")
        val selectedGroup = groupCombo.selectedItem?.toString() ?: allGroups
        val selectedTag = tagCombo.selectedItem?.toString() ?: allTags
        groupCombo.removeAllItems()
        groupCombo.addItem(allGroups)
        scriptManager.getGroups().forEach { groupCombo.addItem(it) }
        tagCombo.removeAllItems()
        tagCombo.addItem(allTags)
        scriptManager.getAllTags().forEach { tagCombo.addItem(it) }
        groupCombo.selectedItem = selectedGroup
        tagCombo.selectedItem = selectedTag
    }

    private fun refreshTable() {
        val allGroups = DeployXBundle.message("script.tab.filter.allGroups")
        val allTags = DeployXBundle.message("script.tab.filter.allTags")
        val selectedId = selectedScript()?.id
        val group = groupCombo.selectedItem?.toString().orEmpty().takeUnless { it == allGroups }.orEmpty()
        val tag = tagCombo.selectedItem?.toString().orEmpty().takeUnless { it == allTags }.orEmpty()
        val scripts = scriptManager.searchScripts(
            keyword = searchField.text.trim(),
            group = group,
            tag = tag
        )
        tableModel.setData(scripts)
        countLabel.text = DeployXBundle.message("script.tab.count", scripts.size)

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
        previewTitle.text = DeployXBundle.message("script.tab.preview.command", script.name.ifBlank { DeployXBundle.message("script.unnamed") })
        previewArea.text = script.command
    }

    private fun clearPreview() {
        previewTitle.text = DeployXBundle.message("script.tab.preview.none")
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
            Messages.showInfoMessage(DeployXBundle.message("script.tab.preview.generated"), DeployXBundle.message("script.tab.preview.title"))
        }
    }

    private fun onCopy() {
        val script = selectedScript() ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(script.command))
        Messages.showInfoMessage(DeployXBundle.message("script.tab.copy.success"), DeployXBundle.message("script.tab.copy.title"))
    }

    private fun onFill(pre: Boolean) {
        val script = selectedScript()
        val dialog = ScriptPickerDialog(project, context(), initialScriptId = script?.id)
        if (dialog.showAndGet()) {
            commandFiller?.invoke(pre, dialog.getResultCommand())
            logAppender?.invoke(null, if (pre) DeployXBundle.message("script.tab.inserted.preCommand") else DeployXBundle.message("script.tab.inserted.postCommand"))
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
        val result = Messages.showYesNoDialog(
            DeployXBundle.message("settings.script.confirm.delete", script.name),
            DeployXBundle.message("settings.script.confirm.delete.title"),
            DeployXBundle.message("settings.script.confirm.delete.yes"),
            DeployXBundle.message("common.cancel"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            scriptManager.deleteScript(script.id)
            refreshAll()
        }
    }

    private class ScriptTableModel : AbstractTableModel() {
        private var columns = arrayOf(
            DeployXBundle.message("script.tab.table.name"),
            DeployXBundle.message("script.tab.table.group"),
            DeployXBundle.message("script.tab.table.server"),
            DeployXBundle.message("script.tab.table.params"),
            DeployXBundle.message("script.tab.table.tags"),
            DeployXBundle.message("script.tab.table.runCount"),
            DeployXBundle.message("script.tab.table.lastRun"),
            DeployXBundle.message("script.tab.table.status")
        )
        private var scripts = listOf<ScriptConfig>()
        private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        /** 语言切换后刷新列名并触发表头重绘。 */
        fun relocalizeColumns() {
            columns = arrayOf(
                DeployXBundle.message("script.tab.table.name"),
                DeployXBundle.message("script.tab.table.group"),
                DeployXBundle.message("script.tab.table.server"),
                DeployXBundle.message("script.tab.table.params"),
                DeployXBundle.message("script.tab.table.tags"),
                DeployXBundle.message("script.tab.table.runCount"),
                DeployXBundle.message("script.tab.table.lastRun"),
                DeployXBundle.message("script.tab.table.status")
            )
            fireTableStructureChanged()
        }

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
                0 -> script.name.ifBlank { DeployXBundle.message("script.unnamed") }
                1 -> script.group.ifBlank { DeployXBundle.message("script.defaultGroup") }
                2 -> script.serverId.ifBlank { DeployXBundle.message("script.selectAtRuntime") }
                3 -> script.params.size
                4 -> script.tags.joinToString(", ")
                5 -> script.runCount
                6 -> if (script.lastRunAt > 0) sdf.format(Date(script.lastRunAt)) else ""
                7 -> script.lastRunStatus.ifBlank { DeployXBundle.message("script.status.notRun") }
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
