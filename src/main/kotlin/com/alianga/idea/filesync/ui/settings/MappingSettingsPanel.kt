package com.alianga.idea.filesync.ui.settings

import com.alianga.idea.filesync.model.MappingConfig
import com.alianga.idea.filesync.service.MappingManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * 目录映射设置面板
 */
class MappingSettingsPanel : JPanel(BorderLayout()) {

    private val mappingManager = MappingManager.getInstance()
    private val tableModel = MappingTableModel()
    private val table = JBTable(tableModel)

    init {
        setupUI()
        refreshTable()
    }

    private fun setupUI() {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { addMapping() }
            .setEditAction { editMapping() }
            .setRemoveAction { removeMapping() }
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Copy", "Copy selected mapping", com.intellij.icons.AllIcons.Actions.Copy) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    copyMapping()
                }
            })

        val panel = decorator.createPanel()
        add(panel, BorderLayout.CENTER)
    }

    private fun refreshTable() {
        tableModel.setData(mappingManager.getMappings())
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
            "确定要删除映射 '${mapping.name}' 吗？",
            "删除映射",
            "删除",
            "取消",
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
        private val columns = arrayOf("Name", "Local Dir", "Server", "Remote Dir", "Backup", "Unzip")
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
                else -> ""
            }
        }
    }
}
