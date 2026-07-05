package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.service.MappingManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * 目录映射设置面板
 */
class MappingSettingsPanel : JPanel(BorderLayout()) {

    private val mappingManager = MappingManager.getInstance()
    private val tableModel = MappingTableModel()
    private val table = JBTable(tableModel)
    private val searchField = JBTextField()

    init {
        setupUI()
        refreshTable()
    }

    private fun setupUI() {
        // 顶部搜索框：实时过滤
        searchField.emptyText.text = DeployXBundle.message("settings.mapping.search.placeholder")
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTable()
            override fun removeUpdate(e: DocumentEvent) = refreshTable()
            override fun changedUpdate(e: DocumentEvent) = refreshTable()
        })
        add(searchField, BorderLayout.NORTH)

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { addMapping() }
            .setEditAction { editMapping() }
            .setRemoveAction { removeMapping() }
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.mapping.action.copy"),
                DeployXBundle.lazyMessage("settings.mapping.action.copy.desc"),
                AllIcons.Actions.Copy
            ) {
                override fun actionPerformed(e: AnActionEvent) { copyMapping() }
            })

        val panel = decorator.createPanel()
        add(panel, BorderLayout.CENTER)
    }

    private fun refreshTable() {
        val keyword = searchField.text.trim().lowercase()
        val allMappings = mappingManager.getMappings()
        val filtered = if (keyword.isEmpty()) {
            allMappings
        } else {
            allMappings.filter {
                it.name.lowercase().contains(keyword) ||
                    it.localDir.lowercase().contains(keyword) ||
                    it.serverId.lowercase().contains(keyword) ||
                    it.remoteDir.lowercase().contains(keyword)
            }
        }
        tableModel.setData(filtered)
    }

    private fun addMapping() {
        val dialog = MappingEditDialog(null)
        if (dialog.showAndGet()) {
            mappingManager.addMapping(dialog.getMappingConfig())
            refreshTable()
        }
    }

    private fun editMapping() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val mapping = tableModel.getMappingAt(selectedRow) ?: return
        val dialog = MappingEditDialog(mapping)
        if (dialog.showAndGet()) {
            mappingManager.updateMapping(mapping.effectiveId, dialog.getMappingConfig())
            refreshTable()
        }
    }

    private fun copyMapping() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val mapping = tableModel.getMappingAt(selectedRow) ?: return
        // 复制时生成新ID，清空名称后缀
        val dialog = MappingEditDialog(mapping, isCopyMode = true)
        if (dialog.showAndGet()) {
            mappingManager.addMapping(dialog.getMappingConfig())
            refreshTable()
        }
    }

    private fun removeMapping() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val mapping = tableModel.getMappingAt(selectedRow) ?: return
        val result = Messages.showYesNoDialog(
            DeployXBundle.message("settings.mapping.confirm.delete", mapping.name),
            DeployXBundle.message("settings.mapping.confirm.delete.title"),
            DeployXBundle.message("settings.mapping.confirm.delete.yes"),
            DeployXBundle.message("common.cancel"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            mappingManager.deleteMapping(mapping.effectiveId)
            refreshTable()
        }
    }

    fun isModified(): Boolean = false

    fun apply() {}

    fun reset() {
        refreshTable()
    }

    private class MappingTableModel : AbstractTableModel() {
        private val columns = arrayOf(
            DeployXBundle.message("settings.mapping.column.name"),
            DeployXBundle.message("settings.mapping.column.localDir"),
            DeployXBundle.message("settings.mapping.column.server"),
            DeployXBundle.message("settings.mapping.column.remoteDir"),
            DeployXBundle.message("settings.mapping.column.backup"),
            DeployXBundle.message("settings.mapping.column.unzip"),
            DeployXBundle.message("settings.mapping.column.preCmd"),
            DeployXBundle.message("settings.mapping.column.postCmd")
        )
        private var mappings = listOf<MappingConfig>()

        fun setData(mappings: List<MappingConfig>) {
            this.mappings = mappings
            fireTableDataChanged()
        }

        fun getMappingAt(row: Int): MappingConfig? = mappings.getOrNull(row)

        override fun getRowCount(): Int = mappings.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val mapping = mappings[rowIndex]
            return when (columnIndex) {
                0 -> mapping.name
                1 -> mapping.localDir
                2 -> mapping.serverId
                3 -> mapping.remoteDir
                4 -> if (mapping.backupEnabled) "✓ ${mapping.backupDir}" else ""
                5 -> if (mapping.unzipEnabled) "✓ ${mapping.unzipDest}" else ""
                6 -> if (mapping.effectivePreCommandEnabled) "✓" else ""
                7 -> if (mapping.effectivePostCommandEnabled) "✓" else ""
                else -> ""
            }
        }
    }
}
