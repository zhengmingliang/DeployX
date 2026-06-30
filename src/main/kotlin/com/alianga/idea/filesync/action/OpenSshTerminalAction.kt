package com.alianga.idea.filesync.action

import com.alianga.idea.filesync.service.ServerManager
import com.alianga.idea.filesync.service.TerminalService
import com.alianga.idea.filesync.ui.dialog.ServerSelectionDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * 打开 SSH 终端 Action
 * 支持从右键菜单和 Go to Action 打开到远程服务器的 SSH 终端
 */
class OpenSshTerminalAction : AnAction("Open SSH Terminal", "Open SSH terminal to remote server", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val serverManager = ServerManager.getInstance()
        val servers = serverManager.getServers()

        when {
            servers.isEmpty() -> {
                showNotification(project, "请先在设置中配置服务器", NotificationType.WARNING)
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX")
            }
            servers.size == 1 -> {
                // 只有一个服务器，直接连接
                openTerminal(project, servers[0])
            }
            else -> {
                // 多个服务器，显示选择对话框
                val dialog = ServerSelectionDialog(
                    servers,
                    "选择服务器",
                    "请选择要连接的远程服务器：",
                    showCommandOptions = false
                )
                if (dialog.showAndGet()) {
                    dialog.selectedServer?.let { server ->
                        openTerminal(project, server)
                    }
                }
            }
        }
    }

    private fun openTerminal(project: Project, serverConfig: com.alianga.idea.filesync.model.ServerConfig) {
        val terminalService = TerminalService.getInstance()
        if (!terminalService.openTerminal(project, serverConfig)) {
            showNotification(
                project,
                "无法打开 SSH 终端，请确保 Terminal 插件已启用",
                NotificationType.ERROR
            )
        } else {
            showNotification(
                project,
                "正在连接到 ${serverConfig.displayAddress}...",
                NotificationType.INFORMATION
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }
}
