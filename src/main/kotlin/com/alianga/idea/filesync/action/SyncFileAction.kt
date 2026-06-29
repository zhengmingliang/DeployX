package com.alianga.idea.filesync.action

import com.alianga.idea.filesync.model.DeployRequest
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
 * 同步文件 Action - 右键菜单 "Sync to Server"
 * 支持多服务器映射时弹出选择对话框
 */
class SyncFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = getSelectedFiles(e)
        if (files.isEmpty()) return

        val firstFile = files.first()
        val localPath = firstFile.path

        // 查找所有匹配的映射，并解析远程目标目录
        val resolvedMappings = MappingManager.getInstance().resolveMappingsByLocalPath(localPath, firstFile.isDirectory)

        if (resolvedMappings.isEmpty()) {
            showNotification(project, "未找到匹配的映射，请先在设置中配置目录映射", NotificationType.WARNING)
            return
        }

        // 收集所有可用的服务器（去重）
        val availableServers = resolvedMappings.map { it.mapping.serverId }.distinct()
            .mapNotNull { ServerManager.getInstance().getServer(it) }

        // 弹出服务器选择（无论几个都弹，让用户确认）
        val serverSelectionDialog = ServerSelectionDialog(
            availableServers,
            "选择目标服务器",
            "文件 ${firstFile.name} 匹配到 ${availableServers.size} 个服务器，请选择："
        )
        if (!serverSelectionDialog.showAndGet()) return
        val targetServer = serverSelectionDialog.selectedServer ?: return

        // 使用选中服务器对应的映射配置
        val targetResolvedMapping = resolvedMappings.firstOrNull { it.mapping.serverId == targetServer.id }
            ?: resolvedMappings.first()
        val targetMapping = targetResolvedMapping.mapping

        // 打开工具窗口
        ToolWindowManager.getInstance(project).getToolWindow("File Sync")?.show()

        val panel = FileSyncToolWindowPanel.activePanel
        if (panel != null) {
            val request = DeployRequest(
                localPath = localPath,
                serverId = targetServer.id,
                remotePath = targetResolvedMapping.resolvedRemoteDir,
                backupDir = if (targetMapping.backupEnabled) targetMapping.backupDir.ifBlank { null } else null,
                backupSource = if (targetMapping.backupEnabled) targetMapping.backupSource.ifBlank { null } else null,
                unzipDest = if (targetMapping.unzipEnabled) targetMapping.unzipDest.ifBlank { null } else null,
                excludePatterns = targetMapping.exclude,
                preCommand = targetMapping.preCommand.ifBlank { null },
                postCommand = targetMapping.postCommand.ifBlank { null }
            )
            panel.executeDeploy(request)
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
