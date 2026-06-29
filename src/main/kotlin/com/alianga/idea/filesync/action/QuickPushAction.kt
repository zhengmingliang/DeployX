package com.alianga.idea.filesync.action

import com.alianga.idea.filesync.service.MappingManager
import com.alianga.idea.filesync.service.ServerManager
import com.alianga.idea.filesync.ui.dialog.ServerSelectionDialog
import com.alianga.idea.filesync.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 快速推送 Action - 右键菜单 "Quick Push to Server"
 */
class QuickPushAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = getSelectedFiles(e)
        if (files.isEmpty()) return

        val firstFile = files.first()
        val localPath = firstFile.path

        val resolvedMappings = MappingManager.getInstance().resolveMappingsByLocalPath(localPath, firstFile.isDirectory)

        if (resolvedMappings.isEmpty()) {
            showNotification(project, "未找到匹配的映射，请先在设置中配置目录映射", NotificationType.WARNING)
            return
        }

        val availableServers = resolvedMappings.map { it.mapping.serverId }.distinct()
            .mapNotNull { ServerManager.getInstance().getServer(it) }

        val targetServerId = if (availableServers.size > 1) {
            val dialog = ServerSelectionDialog(availableServers, "选择推送目标", "快速推送到哪个服务器？")
            if (!dialog.showAndGet()) return
            dialog.selectedServer?.id ?: return
        } else {
            availableServers.firstOrNull()?.id ?: resolvedMappings.first().mapping.serverId
        }

        ToolWindowManager.getInstance(project).getToolWindow("File Sync")?.show()

        val panel = FileSyncToolWindowPanel.activePanel
        if (panel != null) {
            panel.executePush(localPath, targetServerId)
        } else {
            showNotification(project, "工具窗口未打开，请先打开 File Sync 工具窗口", NotificationType.WARNING)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = getSelectedFiles(e).isNotEmpty()
    }

    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> {
        return e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
    }

    private fun showNotification(project: com.intellij.openapi.project.Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("File Sync Tool").createNotification(content, type).notify(project)
    }
}
