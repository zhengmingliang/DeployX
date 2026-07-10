package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.DownloadItem
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile

/**
 * 从服务器拉取文件 Action - download-only（PULL），支持多文件/目录批量下载
 *
 * 根据本地文件路径匹配映射，从对应服务器下载到本地。
 */
class PullFromServerAction : AbstractDeployAction<DownloadItem>() {

    override fun dialogTitle(): String =
        DeployXBundle.message("dialog.server.select.title")

    override fun dialogMessage(fileCount: Int): String =
        DeployXBundle.message("dialog.server.select.messageWithCount", fileCount)

    override fun showCommandOptions(): Boolean = false

    override fun buildItems(
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        targetServer: ServerConfig,
        selection: ServerSelectionResult
    ): List<DownloadItem> {
        return resolvedByFile.mapNotNull { (file, resolvedMappings) ->
            val resolved = resolvedMappings.firstOrNull { it.mapping.serverId == targetServer.id }
                ?: return@mapNotNull null
            val mapping = resolved.mapping
            DownloadItem(
                localPath = file.path,
                isDirectory = file.isDirectory,
                serverId = targetServer.id,
                mappingId = mapping.effectiveId,
                localBaseDir = mapping.localDir,
                remoteBaseDir = mapping.remoteDir,
                relativePath = resolved.relativePath,
                excludePatterns = mapping.exclude
            )
        }
    }

    override fun executeBatch(panel: FileSyncToolWindowPanel, items: List<DownloadItem>) {
        panel.executeDownloadBatch(items)
    }

    override fun actionText(): String = DeployXBundle.message("action.pullFromServer.text")
    override fun actionDescription(): String = DeployXBundle.message("action.pullFromServer.description")
}
