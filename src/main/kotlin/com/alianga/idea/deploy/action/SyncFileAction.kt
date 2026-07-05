package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.UploadItem
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile

/**
 * 同步文件 Action - upload-only，支持多文件/目录批量上传
 *
 * 用户可在服务器选择对话框中手动勾选执行映射中的上传前/后命令。
 */
class SyncFileAction : AbstractDeployAction<UploadItem>() {

    override fun dialogTitle(): String =
        DeployXBundle.message("dialog.server.select.title")

    override fun dialogMessage(fileCount: Int): String =
        DeployXBundle.message("dialog.server.select.messageWithCount", fileCount)

    override fun showCommandOptions(): Boolean = true

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

    override fun actionText(): String = DeployXBundle.message("action.syncFile.text")
    override fun actionDescription(): String = DeployXBundle.message("action.syncFile.description")
}
