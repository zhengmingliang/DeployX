package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.UploadItem
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile

/**
 * 快速推送 Action - upload-only，支持多文件/目录批量上传
 *
 * 与 SyncFileAction 的差异：
 * - 单服务器且无任何命令时跳过服务器选择对话框，直接执行
 * - 仅当存在前/后命令时才显示命令选项勾选框
 */
class QuickPushAction : AbstractDeployAction<UploadItem>() {

    /**
     * 重写服务器选择：单服务器且无命令时跳过对话框
     */
    override fun selectServer(
        servers: List<ServerConfig>,
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        fileCount: Int
    ): ServerSelectionResult? {
        if (servers.isEmpty()) return null
        val commandAvailability = ActionUtils.buildCommandAvailability(resolvedByFile.values.flatten())
        val hasAnyCommand = commandAvailability.values.any { it.hasPreCommand || it.hasPostCommand }

        // 单服务器且无任何命令：直接执行，不弹对话框
        if (servers.size == 1 && !hasAnyCommand) {
            return ServerSelectionResult(
                server = servers.first(),
                executePreCommand = false,
                executePostCommand = false
            )
        }

        val dialog = ServerSelectionDialog(
            servers,
            dialogTitle(),
            dialogMessage(fileCount),
            showCommandOptions = hasAnyCommand,
            commandAvailabilityByServerId = commandAvailability
        )
        if (!dialog.showAndGet()) return null
        val server = dialog.selectedServer ?: return null
        return ServerSelectionResult(server, dialog.executePreCommand, dialog.executePostCommand)
    }

    override fun dialogTitle(): String =
        DeployXBundle.message("dialog.server.select.title.push")

    override fun dialogMessage(fileCount: Int): String =
        DeployXBundle.message("dialog.server.select.message.push", fileCount)

    override fun buildItems(
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        targetServer: ServerConfig,
        selection: ServerSelectionResult
    ): List<UploadItem> {
        return resolvedByFile.mapNotNull { (file, resolvedMappings) ->
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
    }

    override fun executeBatch(panel: FileSyncToolWindowPanel, items: List<UploadItem>) {
        panel.executeUploadBatch(items)
    }

    override fun actionText(): String = DeployXBundle.message("action.quickPush.text")
    override fun actionDescription(): String = DeployXBundle.message("action.quickPush.description")
}
