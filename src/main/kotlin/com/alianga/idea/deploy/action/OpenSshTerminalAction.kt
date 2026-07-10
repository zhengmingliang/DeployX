package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.service.TerminalService
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * 打开 SSH 终端 Action
 * 支持从右键菜单和 Go to Action 打开到远程服务器的 SSH 终端，支持多选同时打开多个终端
 */
class OpenSshTerminalAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val serverManager = ServerManager.getInstance()
        val servers = serverManager.getServers()

        when {
            servers.isEmpty() -> {
                showNotification(project, DeployXBundle.message("notification.configServerFirst"), NotificationType.WARNING)
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX")
            }
            servers.size == 1 -> {
                // 只有一个服务器，直接连接
                openTerminal(project, servers[0])
            }
            else -> {
                // 多个服务器，显示选择对话框，支持多选
                val dialog = ServerSelectionDialog(
                    servers,
                    DeployXBundle.message("dialog.server.select.title.connect"),
                    DeployXBundle.message("dialog.server.select.message.connect"),
                    showCommandOptions = false
                )
                if (dialog.showAndGet()) {
                    val selected = dialog.selectedServers.ifEmpty { listOfNotNull(dialog.selectedServer) }
                    selected.forEach { server ->
                        openTerminal(project, server)
                    }
                }
            }
        }
    }

    private fun openTerminal(project: Project, serverConfig: com.alianga.idea.deploy.model.ServerConfig) {
        val terminalService = TerminalService.getInstance()
        if (!terminalService.openTerminal(project, serverConfig)) {
            showNotification(
                project,
                DeployXBundle.message("notification.cannotOpenTerminal"),
                NotificationType.ERROR
            )
        } else {
            showNotification(
                project,
                DeployXBundle.message("notification.connecting", serverConfig.displayAddress),
                NotificationType.INFORMATION
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = DeployXBundle.message("action.openSshTerminal.text")
        e.presentation.description = DeployXBundle.message("action.openSshTerminal.description")
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }
}
