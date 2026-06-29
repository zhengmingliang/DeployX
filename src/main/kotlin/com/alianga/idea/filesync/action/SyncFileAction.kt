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
 * 支持多文件/目录批量同步
 */
class SyncFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = getSelectedFiles(e)
        if (files.isEmpty()) return

        val mappingManager = MappingManager.getInstance()
        val resolvedByFile = files.associateWith { file ->
            mappingManager.resolveMappingsByLocalPath(file.path, file.isDirectory)
        }.filterValues { it.isNotEmpty() }

        if (resolvedByFile.isEmpty()) {
            showNotification(project, "未找到匹配的映射，请先在设置中配置目录映射", NotificationType.WARNING)
            return
        }

        val availableServers = resolvedByFile.values.flatten()
            .map { it.mapping.serverId }
            .distinct()
            .mapNotNull { ServerManager.getInstance().getServer(it) }

        val serverSelectionDialog = ServerSelectionDialog(
            availableServers,
            "选择目标服务器",
            "已选择 ${files.size} 个文件/目录，请选择上传目标服务器："
        )
        if (!serverSelectionDialog.showAndGet()) return
        val targetServer = serverSelectionDialog.selectedServer ?: return

        ToolWindowManager.getInstance(project).getToolWindow("File Sync")?.show()

        val requests = resolvedByFile.mapNotNull { (file, resolvedMappings) ->
            val resolved = resolvedMappings.firstOrNull { it.mapping.serverId == targetServer.id }
                ?: return@mapNotNull null
            val mapping = resolved.mapping
            DeployRequest(
                localPath = file.path,
                serverId = targetServer.id,
                remotePath = resolved.resolvedRemoteDir,
                backupDir = if (mapping.backupEnabled) mapping.backupDir.ifBlank { null } else null,
                backupSource = if (mapping.backupEnabled) mapping.backupSource.ifBlank { null } else null,
                unzipDest = if (mapping.unzipEnabled) mapping.unzipDest.ifBlank { null } else null,
                excludePatterns = mapping.exclude,
                preCommand = if (mapping.effectivePreCommandEnabled) mapping.preCommand.ifBlank { null } else null,
                postCommand = if (mapping.effectivePostCommandEnabled) mapping.postCommand.ifBlank { null } else null
            )
        }

        val skipped = files.size - requests.size
        val panel = FileSyncToolWindowPanel.activePanel
        if (panel != null) {
            if (skipped > 0) panel.appendLog("[WARN] 有 $skipped 个文件没有匹配到目标服务器 ${targetServer.id} 的映射，已跳过")
            panel.executeDeployBatch(requests)
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
