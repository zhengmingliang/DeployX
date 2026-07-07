package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
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
import javax.swing.ListSelectionModel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * 设置页脚本库管理面板。
 */
class ScriptSettingsPanel : JPanel(BorderLayout()) {

    private val scriptManager = ScriptManager.getInstance()
    private val tableModel = ScriptTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
    }
    private val searchField = JBTextField()
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    init {
        setupUI()
        refreshTable()
    }

    private fun setupUI() {
        searchField.emptyText.text = DeployXBundle.message("settings.script.search.placeholder")
        // 实时过滤：输入即过滤，无需按 Enter
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTable()
            override fun removeUpdate(e: DocumentEvent) = refreshTable()
            override fun changedUpdate(e: DocumentEvent) = refreshTable()
        })
        add(searchField, BorderLayout.NORTH)

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { addScript() }
            .setEditAction { editScript() }
            .setRemoveAction { removeScript() }
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.script.action.copy"),
                DeployXBundle.lazyMessage("settings.script.action.copy.desc"),
                AllIcons.Actions.Copy
            ) { override fun actionPerformed(e: AnActionEvent) { copyScript() } })
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.script.action.import"),
                DeployXBundle.lazyMessage("settings.script.action.import.desc"),
                AllIcons.ToolbarDecorator.Import
            ) { override fun actionPerformed(e: AnActionEvent) { importScripts() } })
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.script.action.export"),
                DeployXBundle.lazyMessage("settings.script.action.export.desc"),
                AllIcons.ToolbarDecorator.Export
            ) { override fun actionPerformed(e: AnActionEvent) { exportScripts() } })
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.script.action.refresh"),
                DeployXBundle.lazyMessage("settings.script.action.refresh.desc"),
                AllIcons.Actions.Refresh
            ) { override fun actionPerformed(e: AnActionEvent) { refreshTable() } })

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
        val scripts = selectedScripts()
        if (scripts.isEmpty()) return
        val names = scripts.joinToString(", ") { it.name }
        val result = Messages.showYesNoDialog(
            DeployXBundle.message("settings.script.confirm.delete.multi", names, scripts.size),
            DeployXBundle.message("settings.script.confirm.delete.title"),
            DeployXBundle.message("settings.script.confirm.delete.yes"),
            DeployXBundle.message("common.cancel"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            scriptManager.deleteScripts(scripts.map { it.id })
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
            Messages.showInfoMessage(DeployXBundle.message("settings.script.imported", count), DeployXBundle.message("settings.script.import.complete"))
        } catch (e: Exception) {
            Messages.showErrorDialog(DeployXBundle.message("settings.script.import.failed", e.message ?: ""), DeployXBundle.message("settings.script.import.failed.title"))
        }
    }

    private fun exportScripts() {
        val scripts = selectedScripts().ifEmpty { scriptManager.getScripts() }
        if (scripts.isEmpty()) return
        val chooser = JFileChooser().apply {
            selectedFile = if (scripts.size == 1) {
                File("deployx-script-${scripts[0].name.ifBlank { scripts[0].id }}.json")
            } else {
                File("deployx-scripts.json")
            }
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val json = if (scripts.size == 1) gson.toJson(scripts[0]) else gson.toJson(scripts)
        chooser.selectedFile.writeText(json)
        Messages.showInfoMessage(DeployXBundle.message("settings.script.exported", chooser.selectedFile.absolutePath), DeployXBundle.message("settings.script.export.complete"))
    }

    private fun selectedScripts(): List<ScriptConfig> {
        return table.selectedRows.map { row ->
            tableModel.getScriptAt(table.convertRowIndexToModel(row))
        }.filterNotNull()
    }

    private fun selectedScript(): ScriptConfig? = selectedScripts().firstOrNull()

    fun isModified(): Boolean = false
    fun apply() {}
    fun reset() = refreshTable()

    private class ScriptTableModel : AbstractTableModel() {
        private val columns = arrayOf(
            DeployXBundle.message("settings.script.table.name"),
            DeployXBundle.message("settings.script.table.group"),
            DeployXBundle.message("settings.script.table.server"),
            DeployXBundle.message("settings.script.table.params"),
            DeployXBundle.message("settings.script.table.tags"),
            DeployXBundle.message("settings.script.table.lastRun"),
            DeployXBundle.message("settings.script.table.status")
        )
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
                2 -> script.serverId.ifBlank { DeployXBundle.message("settings.script.server.selectAtRuntime") }
                3 -> script.params.size
                4 -> script.tags.joinToString(", ")
                5 -> if (script.lastRunAt > 0) sdf.format(Date(script.lastRunAt)) else ""
                6 -> script.lastRunStatus
                else -> ""
            }
        }
    }
}
