package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ui.toolwindow.RemoteFileBrowserToolWindowFactory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 打开远程文件浏览器 Action。
 *
 * 激活/显示「DeployX Remote」侧边栏工具窗口（非模态），在树形结构中浏览远程服务器文件系统，
 * 支持查看、编辑（IDE 主编辑器 + 语法高亮）、下载、拖拽上传/下载。
 *
 * 可通过右键菜单、Alt+Shift+Z 快捷菜单、DeployX 工具窗口按钮触发。
 */
class OpenRemoteFileBrowserAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (ServerManager.getInstance().getServers().isEmpty()) {
            showNotification(project, DeployXBundle.message("notification.configServerFirst"), NotificationType.WARNING)
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "DeployX")
            return
        }
        ToolWindowManager.getInstance(project)
            .getToolWindow(RemoteFileBrowserToolWindowFactory.TOOL_WINDOW_ID)?.show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = DeployXBundle.message("action.openRemoteBrowser.text")
        e.presentation.description = DeployXBundle.message("action.openRemoteBrowser.description")
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }
}
