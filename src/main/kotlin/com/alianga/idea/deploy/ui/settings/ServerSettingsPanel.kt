package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ssh.SshConnection
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * 服务器管理设置面板
 */
class ServerSettingsPanel : JPanel(BorderLayout()) {

    private val serverManager = ServerManager.getInstance()
    private val tableModel = ServerTableModel()
    private val table = JBTable(tableModel)
    private val searchField = JBTextField()

    init {
        setupUI()
        refreshTable()
    }

    private fun setupUI() {
        // 顶部搜索框：实时过滤
        searchField.emptyText.text = DeployXBundle.message("settings.server.search.placeholder")
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTable()
            override fun removeUpdate(e: DocumentEvent) = refreshTable()
            override fun changedUpdate(e: DocumentEvent) = refreshTable()
        })
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(searchField, BorderLayout.CENTER)
        }
        add(searchPanel, BorderLayout.NORTH)

        // 双击行直接编辑（与工具栏铅笔等效）
        object : com.intellij.ui.DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                if (table.rowAtPoint(e.point) < 0) return false
                editServer()
                return true
            }
        }.installOn(table)

        // 创建带工具栏的表格
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { addServer() }
            .setEditAction { editServer() }
            .setRemoveAction { removeServer() }
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.server.action.copy"),
                DeployXBundle.lazyMessage("settings.server.action.copy.desc"),
                AllIcons.Actions.Copy
            ) {
                override fun actionPerformed(e: AnActionEvent) { copyServer() }
            })
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.server.action.testConnection"),
                DeployXBundle.lazyMessage("settings.server.action.testConnection.desc"),
                AllIcons.Actions.Execute
            ) {
                override fun actionPerformed(e: AnActionEvent) { testConnection() }
            })
            .addExtraAction(object : AnAction(
                DeployXBundle.lazyMessage("settings.server.action.setDefault"),
                DeployXBundle.lazyMessage("settings.server.action.setDefault.desc"),
                AllIcons.Actions.Checked
            ) {
                override fun actionPerformed(e: AnActionEvent) { setDefaultServer() }
            })

        val panel = decorator.createPanel()
        add(panel, BorderLayout.CENTER)
    }

    private fun refreshTable() {
        val keyword = searchField.text.trim().lowercase()
        val allServers = serverManager.getServers()
        val filtered = if (keyword.isEmpty()) {
            allServers
        } else {
            allServers.filter {
                it.id.lowercase().contains(keyword) ||
                    it.name.lowercase().contains(keyword) ||
                    it.host.lowercase().contains(keyword) ||
                    it.user.lowercase().contains(keyword)
            }
        }
        tableModel.setData(filtered)
    }

    private fun addServer() {
        val dialog = ServerEditDialog(null)
        if (dialog.showAndGet()) {
            serverManager.addServer(dialog.getServerConfig())
            refreshTable()
        }
    }

    private fun editServer() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val server = tableModel.getServerAt(selectedRow) ?: return
        val dialog = ServerEditDialog(server)
        if (dialog.showAndGet()) {
            serverManager.updateServer(server.id, dialog.getServerConfig())
            refreshTable()
        }
    }

    private fun copyServer() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val server = tableModel.getServerAt(selectedRow) ?: return
        val dialog = ServerEditDialog(server, isCopyMode = true)
        if (dialog.showAndGet()) {
            serverManager.addServer(dialog.getServerConfig())
            refreshTable()
        }
    }

    private fun removeServer() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val server = tableModel.getServerAt(selectedRow) ?: return
        val result = Messages.showYesNoDialog(
            DeployXBundle.message("settings.server.confirm.delete", server.name),
            DeployXBundle.message("settings.server.confirm.delete.title"),
            DeployXBundle.message("settings.server.confirm.delete.yes"),
            DeployXBundle.message("common.cancel"),
            Messages.getQuestionIcon()
        )
        if (result == Messages.YES) {
            serverManager.deleteServer(server.id)
            refreshTable()
        }
    }

    private fun testConnection() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val server = tableModel.getServerAt(selectedRow) ?: return

        // 在后台线程中测试连接
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(null, DeployXBundle.message("settings.server.connection.testing"), true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.text = DeployXBundle.message("settings.server.connection.connecting", server.displayAddress)
                    val connection = SshConnection(server)
                    val result = connection.testConnection()

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        if (result.success) {
                            Messages.showInfoMessage(result.message, DeployXBundle.message("settings.server.connection.success.title"))
                        } else {
                            Messages.showErrorDialog(result.message, DeployXBundle.message("settings.server.connection.failed.title"))
                        }
                    }
                }
            }
        )
    }

    private fun setDefaultServer() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val server = tableModel.getServerAt(selectedRow) ?: return
        serverManager.setDefaultServer(server.id)
        refreshTable()
    }

    fun isModified(): Boolean = false // 服务器管理是即时保存的

    /** 切换语言后刷新列头与占位文案 */
    fun relocalize() {
        searchField.emptyText.text = DeployXBundle.message("settings.server.search.placeholder")
        tableModel.relocalize()
    }

    fun apply() {
        // 服务器管理是即时保存的，不需要额外操作
    }

    fun reset() {
        refreshTable()
    }

    /**
     * 服务器表格模型
     */
    private class ServerTableModel : AbstractTableModel() {
        // 列名存储 bundle 键，getColumnName 实时取文案，语言切换后无需重建模型
        private val columnKeys = arrayOf(
            "settings.server.column.id",
            "settings.server.column.name",
            "settings.server.column.type",
            "settings.server.column.host",
            "settings.server.column.port",
            "settings.server.column.user",
            "settings.server.column.auth",
            "settings.server.column.default"
        )
        private var servers = listOf<ServerConfig>()

        fun relocalize() = fireTableStructureChanged()

        fun setData(servers: List<ServerConfig>) {
            this.servers = servers
            fireTableDataChanged()
        }

        fun getServerAt(row: Int): ServerConfig? =
            servers.getOrNull(row)

        override fun getRowCount(): Int = servers.size
        override fun getColumnCount(): Int = columnKeys.size
        override fun getColumnName(column: Int): String = DeployXBundle.message(columnKeys[column])

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val server = servers[rowIndex]
            return when (columnIndex) {
                0 -> server.id
                1 -> server.name
                2 -> if (server.isLocal) DeployXBundle.message("dialog.server.type.local") else DeployXBundle.message("dialog.server.type.ssh")
                3 -> server.host
                4 -> if (server.isLocal) "-" else server.port.toString()
                5 -> if (server.isLocal) "-" else server.user
                6 -> if (server.isLocal) "-" else server.authType.value
                7 -> if (server.isDefault) "✓" else ""
                else -> ""
            }
        }
    }
}
