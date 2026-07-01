package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.service.ScriptManager
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * 设置页脚本库管理面板。
 */
class ScriptSettingsPanel : JPanel(BorderLayout()) {

    private val scriptManager = ScriptManager.getInstance()
    private val tableModel = ScriptTableModel()
    private val table = JBTable(tableModel)
    private val searchField = JBTextField()
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    init {
        setupUI()
        refreshTable()
    }

    private fun setupUI() {
        searchField.emptyText.text = "搜索脚本名称、描述、标签或命令"
        searchField.addActionListener { refreshTable() }
        add(searchField, BorderLayout.NORTH)

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { addScript() }
            .setEditAction { editScript() }
            .setRemoveAction { removeScript() }
            .addExtraAction(object : AnAction("Copy", "Copy selected script", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) { copyScript() }
            })
            .addExtraAction(object : AnAction("Import", "Import scripts from JSON", AllIcons.ToolbarDecorator.Import) {
                override fun actionPerformed(e: AnActionEvent) { importScripts() }
            })
            .addExtraAction(object : AnAction("Export", "Export selected script", AllIcons.ToolbarDecorator.Export) {
                override fun actionPerformed(e: AnActionEvent) { exportSelected() }
            })
            .addExtraAction(object : AnAction("Refresh", "Refresh scripts", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) { refreshTable() }
            })

        add(decorator.createPanel(), BorderLayout.CENTER)
    }

    fun refreshTable() {
        tableModel.setData(scriptManager.searchScripts(searchField.text.trim()))
    }

    private fun addScript() {
        val dialog = ScriptEditDialog(null)
        if (dialog.showAndGet()) {
            scriptManager.addScript(dialog.getScriptConfig())
            refreshTable()
        }
    }

    private fun editScript() {
        val script = selectedScript() ?: return
        val dialog = ScriptEditDialog(script)
        if (dialog.showAndGet()) {
            scriptManager.updateScript(script.id, dialog.getScriptConfig())
            refreshTable()
        }
    }

    private fun copyScript() {
        val script = selectedScript() ?: return
        scriptManager.copyScript(script.id)
        refreshTable()
    }

    private fun removeScript() {
        val script = selectedScript() ?: return
        val result = Messages.showYesNoDialog(
            "确定要删除脚本 '${script.name}' 吗？",
            "删除脚本",
            "删除",
            "取消",
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            scriptManager.deleteScript(script.id)
            refreshTable()
        }
    }

    private fun importScripts() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        try {
            val text = chooser.selectedFile.readText()
            val listType = object : TypeToken<List<ScriptConfig>>() {}.type
            val imported: List<ScriptConfig> = try {
                gson.fromJson(text, listType)
            } catch (_: Exception) {
                listOf(gson.fromJson(text, ScriptConfig::class.java))
            }
            val count = scriptManager.importScripts(imported.filterNotNull())
            refreshTable()
            Messages.showInfoMessage("已导入 $count 个脚本", "导入完成")
        } catch (e: Exception) {
            Messages.showErrorDialog("导入失败: ${e.message}", "导入脚本")
        }
    }

    private fun exportSelected() {
        val script = selectedScript() ?: return
        val chooser = JFileChooser().apply {
            selectedFile = File("deployx-script-${script.name.ifBlank { script.id }}.json")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        chooser.selectedFile.writeText(gson.toJson(script))
        Messages.showInfoMessage("脚本已导出: ${chooser.selectedFile.absolutePath}", "导出完成")
    }

    private fun selectedScript(): ScriptConfig? {
        val row = table.selectedRow
        if (row < 0) return null
        return tableModel.getScriptAt(table.convertRowIndexToModel(row))
    }

    fun isModified(): Boolean = false
    fun apply() {}
    fun reset() = refreshTable()

    private class ScriptTableModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Group", "Server", "Params", "Tags", "Last Run", "Status")
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
                0 -> script.name
                1 -> script.group
                2 -> script.serverId.ifBlank { "运行时选择" }
                3 -> script.params.size
                4 -> script.tags.joinToString(", ")
                5 -> if (script.lastRunAt > 0) sdf.format(Date(script.lastRunAt)) else ""
                6 -> script.lastRunStatus
                else -> ""
            }
        }
    }
}
