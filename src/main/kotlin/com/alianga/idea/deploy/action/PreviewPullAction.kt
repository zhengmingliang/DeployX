package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.DownloadItem
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile

/**
 * 1.0.8 新增：预览拉取 Action - 选中文件/目录后预览从服务器下载的文件清单（dry-run）。
 *
 * 复用 [PullFromServerAction] 的映射解析 / 服务器选择 / Item 构造流程，
 * 区别仅在 [executeBatch] 走面板的 [FileSyncToolWindowPanel.executePreviewPullBatch]（dry-run），
 * 不写入历史。
 */
class PreviewPullAction : AbstractDeployAction<DownloadItem>() {

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
            // 与 PullFromServerAction 一致：1.0.8 起使用最长前缀匹配（MappingManager.pickMostSpecific 已自动选好），
            // 再按目标服务器 id 过滤；当嵌套映射属于不同服务器时取最具体的、且属于目标服务器的那一个。
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
        panel.executePreviewPullBatch(items)
    }

    override fun actionText(): String = DeployXBundle.message("action.previewPull.text")
    override fun actionDescription(): String = DeployXBundle.message("action.previewPull.description")
}
