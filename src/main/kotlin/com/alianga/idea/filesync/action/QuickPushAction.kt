package com.alianga.idea.filesync.action

import com.alianga.idea.filesync.model.ServerConfig
import com.alianga.idea.filesync.model.UploadItem
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
 * 快速推送 Action - upload-only，支持多文件/目录批量上传
 */
class QuickPushAction : AnAction() {

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

        val commandAvailability = buildCommandAvailability(resolvedByFile.values.flatten())
        val hasAnyCommand = commandAvailability.values.any { it.hasPreCommand || it.hasPostCommand }
        val selection = selectServer(availableServers, commandAvailability, hasAnyCommand, files.size) ?: return
        val targetServer = selection.server

        ToolWindowManager.getInstance(project).getToolWindow("File Sync")?.show()

        val items = resolvedByFile.mapNotNull { (file, resolvedMappings) ->
            val resolved = resolvedMappings.firstOrNull { it.mapping.serverId == targetServer.id }
                ?: return@mapNotNull null
            val mapping = resolved.mapping
            UploadItem(
                localPath = file.path,
                isDirectory = file.isDirectory,
                serverId = targetServer.id,
                mappingId = mapping.effectiveId,
                sourceBaseDir = mapping.localDir,
                remoteBaseDir = mapping.remoteDir,
                relativePath = resolved.relativePath,
                excludePatterns = mapping.exclude,
                preCommand = if (selection.executePreCommand && mapping.effectivePreCommandEnabled) mapping.preCommand.ifBlank { null } else null,
                postCommand = if (selection.executePostCommand && mapping.effectivePostCommandEnabled) mapping.postCommand.ifBlank { null } else null
            )
        }

        val skipped = files.size - items.size
        val panel = FileSyncToolWindowPanel.getPanel(project)
        if (panel != null) {
            if (skipped > 0) panel.appendLog("[WARN] 有 $skipped 个文件没有匹配到目标服务器 ${targetServer.id} 的映射，已跳过")
            panel.executeUploadBatch(items)
        } else {
            showNotification(project, "工具窗口未打开，请先打开 File Sync 工具窗口", NotificationType.WARNING)
        }
    }

    private data class ServerSelection(
        val server: ServerConfig,
        val executePreCommand: Boolean,
        val executePostCommand: Boolean
    )

    private fun selectServer(
        servers: List<ServerConfig>,
        commandAvailability: Map<String, ServerSelectionDialog.CommandAvailability>,
        hasAnyCommand: Boolean,
        selectedCount: Int
    ): ServerSelection? {
        if (servers.isEmpty()) return null
        if (servers.size == 1 && !hasAnyCommand) {
            return ServerSelection(servers.first(), executePreCommand = false, executePostCommand = false)
        }
        val dialog = ServerSelectionDialog(
            servers,
            "选择推送目标",
            "已选择 $selectedCount 个文件/目录，请选择快速推送目标：",
            showCommandOptions = hasAnyCommand,
            commandAvailabilityByServerId = commandAvailability
        )
        if (!dialog.showAndGet()) return null
        val server = dialog.selectedServer ?: return null
        return ServerSelection(server, dialog.executePreCommand, dialog.executePostCommand)
    }

    private fun buildCommandAvailability(resolvedMappings: List<MappingManager.ResolvedMapping>): Map<String, ServerSelectionDialog.CommandAvailability> {
        return resolvedMappings.groupBy { it.mapping.serverId }.mapValues { (_, values) ->
            ServerSelectionDialog.CommandAvailability(
                hasPreCommand = values.any { it.mapping.effectivePreCommandEnabled && it.mapping.preCommand.isNotBlank() },
                hasPostCommand = values.any { it.mapping.effectivePostCommandEnabled && it.mapping.postCommand.isNotBlank() }
            )
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
