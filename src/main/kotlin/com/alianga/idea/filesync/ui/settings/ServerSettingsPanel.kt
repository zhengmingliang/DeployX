package com.alianga.idea.filesync.ui.settings

import com.alianga.idea.filesync.model.ServerConfig
import com.alianga.idea.filesync.service.ServerManager
import com.alianga.idea.filesync.ssh.SshConnection
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
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Copy", "Copy selected server", com.intellij.icons.AllIcons.Actions.Copy) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    copyServer()
                }
            })
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Test Connection", "Test SSH connection", com.intellij.icons.AllIcons.Actions.Execute) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    testConnection()
                }
            })
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Set Default", "Set as default server", com.intellij.icons.AllIcons.Actions.Checked) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    setDefaultServer()
                }
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
            "确定要删除服务器 '${server.name}' 吗？",
            "删除服务器",
            "删除",
            "取消",
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
            object : com.intellij.openapi.progress.Task.Backgroundable(null, "Testing Connection...", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    indicator.text = "Connecting to ${server.displayAddress}..."
                    val connection = SshConnection(server)
                    val result = connection.testConnection()

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        if (result.success) {
                            Messages.showInfoMessage(result.message, "Connection Test")
                        } else {
                            Messages.showErrorDialog(result.message, "Connection Test Failed")
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
        private val columns = arrayOf("ID", "Name", "Host", "Port", "User", "Auth", "Default")
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
