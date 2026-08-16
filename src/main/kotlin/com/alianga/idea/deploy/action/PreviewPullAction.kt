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
            // 1.0.8：嵌套映射场景下，按最长 localDir 前缀选最具体的映射（与 PullFromServerAction 保持一致）
            val resolved = ActionUtils.pickMostSpecificByServer(resolvedMappings, targetServer.id)
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
