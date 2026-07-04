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
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * 服务器管理设置面板
 */
class ServerSettingsPanel : JPanel(BorderLayout()) {

    private val serverManager = ServerManager.getInstance()
    private val tableModel = ServerTableModel()
    private val table = JBTable(tableModel)

    init {
        setupUI()
        refreshTable()
    }

    private fun setupUI() {
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
        tableModel.setData(serverManager.getServers())
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
        private val columns = arrayOf(
            DeployXBundle.message("settings.server.column.id"),
            DeployXBundle.message("settings.server.column.name"),
            DeployXBundle.message("settings.server.column.host"),
            DeployXBundle.message("settings.server.column.port"),
            DeployXBundle.message("settings.server.column.user"),
            DeployXBundle.message("settings.server.column.auth"),
            DeployXBundle.message("settings.server.column.default")
        )
        private var servers = listOf<ServerConfig>()

        fun setData(servers: List<ServerConfig>) {
            this.servers = servers
            fireTableDataChanged()
        }

        fun getServerAt(row: Int): ServerConfig? =
            servers.getOrNull(row)

        override fun getRowCount(): Int = servers.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val server = servers[rowIndex]
            return when (columnIndex) {
                0 -> server.id
                1 -> server.name
                2 -> server.host
                3 -> server.port
                4 -> server.user
                5 -> server.authType.value
                6 -> if (server.isDefault) "✓" else ""
                else -> ""
            }
        }
    }
}
