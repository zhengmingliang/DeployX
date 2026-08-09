package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.service.MappingManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.ButtonGroup
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * 目录映射设置面板
 *
 * 顶部提供「项目视图 / 全部视图」单选切换（1.0.7 新增）：
 * - 项目视图（默认）：只展示属于当前最近激活项目的映射（按映射 localDir 是否落在项目 basePath 之下判断）
 * - 全部视图：展示所有映射（1.0.7 之前的行为）
 *
 * 因本面板为应用级配置（applicationConfigurable），无法直接拿到「当前项目」，
 * 故用 [ProjectManager.openProjects] 的全部项目作为「项目集合」--映射 localDir 属于
 * 任一已打开项目即在项目视图中展示，确保当前项目必然命中。
 * 无打开项目时自动回退到全部视图。
 */
class MappingSettingsPanel : JPanel(BorderLayout()) {

    private val mappingManager = MappingManager.getInstance()
    private val tableModel = MappingTableModel()
    private val table = JBTable(tableModel)
    private val searchField = JBTextField()

    /** 视图切换：项目视图（默认）/ 全部视图 */
    private val projectViewRadio = JBRadioButton(DeployXBundle.message("settings.mapping.view.project"), true)
    private val allViewRadio = JBRadioButton(DeployXBundle.message("settings.mapping.view.all"), false)
    /** 项目视图不可用（无打开项目）时的提示 */
    private val projectUnavailableLabel = JBLabel(DeployXBundle.message("settings.mapping.view.projectUnavailable")).apply {
        foreground = com.intellij.ui.JBColor.GRAY
        isVisible = false
    }

    init {
        setupUI()
        refreshTable()
    }

    private fun setupUI() {
        // 顶部：视图切换 + 搜索框
        ButtonGroup().apply { add(projectViewRadio); add(allViewRadio) }
        projectViewRadio.addActionListener { refreshTable() }
        allViewRadio.addActionListener { refreshTable() }

        val viewPanel = JPanel(BorderLayout(8, 0)).apply {
            border = com.intellij.util.ui.JBUI.Borders.empty(0, 0, 4, 0)
            val radios = JPanel().apply {
                layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)
                add(projectViewRadio)
                add(allViewRadio)
            }
            add(radios, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            add(projectUnavailableLabel, BorderLayout.EAST)
        }
        add(viewPanel, BorderLayout.NORTH)

        // 搜索框：实时过滤
        searchField.emptyText.text = DeployXBundle.message("settings.mapping.search.placeholder")
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTable()
            override fun removeUpdate(e: DocumentEvent) = refreshTable()
            override fun changedUpdate(e: DocumentEvent) = refreshTable()
        })

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

    /**
     * 取所有已打开项目的 basePath（归一化、去尾斜杠）。
     *
     * 本面板为应用级配置（applicationConfigurable），无法直接拿到「当前项目」。
     * [ProjectManager.openProjects] 的数组顺序不保证是「最近激活」顺序，单取末尾
     * 项目可能选错（用户反馈：当前打开的项目映射未展示）。故项目视图改为：
     * 映射 localDir 属于**任意一个**已打开项目即展示，确保当前项目必然命中。
     *
     * 数组末尾（最近打开的）排在最前，便于后续按项目展示分组/排序。
     */
    private fun resolveOpenProjectPaths(): List<String> {
        val projects = ProjectManager.getInstance().openProjects
        // 末尾项目（最近打开的）排前，避免被早期项目覆盖
        return projects.reversed()
            .mapNotNull { it.basePath?.let { p -> normalizePath(p) } }
            .filter { it.isNotBlank() }
    }

    private fun normalizePath(path: String): String = path.replace("\\", "/").trimEnd('/')

    /**
     * 判断映射是否属于任一已打开项目（与 [MappingManager.findMappingsByLocalPath] 同口径）：
     * 映射 localDir 归一化后等于某项目根，或位于某项目根之下（startsWith "$base/"）。
     */
    private fun belongsToAnyOpenProject(mapping: MappingConfig, projectBases: List<String>): Boolean {
        val localDir = normalizePath(mapping.localDir)
        return projectBases.any { base -> localDir == base || localDir.startsWith("$base/") }
    }

    private fun refreshTable() {
        val keyword = searchField.text.trim().lowercase()
        var allMappings = mappingManager.getMappings()

        // 项目视图：按所有已打开项目过滤；无打开项目时回退到全部视图并提示
        val projectBases = resolveOpenProjectPaths()
        val projectViewAvailable = projectBases.isNotEmpty()
        if (projectViewRadio.isSelected && projectViewAvailable) {
            allMappings = allMappings.filter { belongsToAnyOpenProject(it, projectBases) }
        } else if (projectViewRadio.isSelected && !projectViewAvailable) {
            // 无打开项目：回退到全部视图，切换单选并提示
            allViewRadio.isSelected = true
        }
        projectUnavailableLabel.isVisible = !projectViewAvailable

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
