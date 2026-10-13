package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.UploadItem
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile

/**
 * 预览同步 Action - 使用与实际 upload-only 相同的 --files-from dry-run 逻辑
 *
 * 1.1.1：与部署 / 推送统一走 [ServerSelectionDialog]（无可用服务器时对话框内显示空状态，
 * 不再弹通知；嵌套映射歧义在对话框内改选，替代旧的 MappingSelectionDialog）。
 * 单服务器且无映射歧义时跳过对话框直接预览（与旧行为一致）。预览不执行任何命令。
 */
class PreviewSyncAction : AbstractDeployAction<UploadItem>() {

    override fun selectServers(
        servers: List<ServerConfig>,
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        fileCount: Int
    ): MultiServerSelectionResult? {
        if (servers.isEmpty()) return null
        val resolvedByServerId = ActionUtils.buildResolvedByServerId(resolvedByFile)
        if (servers.size == 1 && !ActionUtils.hasMappingChoices(resolvedByServerId)) {
            return MultiServerSelectionResult(listOf(servers.first()), executePreCommand = false, executePostCommand = false)
        }
        val dialog = ServerSelectionDialog(
            servers,
            dialogTitle(),
            dialogMessage(fileCount),
            showCommandOptions = false,
            resolvedByServerId = resolvedByServerId,
            okText = okText(),
            hiddenServerCount = hiddenServerCount,
            selectedPaths = selectedPaths
        )
        if (!dialog.showAndGet()) return null
        val selected = dialog.selectedServers.ifEmpty { listOfNotNull(dialog.selectedServer) }
        if (selected.isEmpty()) return null
        return MultiServerSelectionResult(selected, false, false, mappingIdsByServerId = dialog.selectedMappingIdsByServerId)
    }

    override fun okText(): String = DeployXBundle.message("dialog.server.select.ok.preview")
    override fun dialogTitle(): String = DeployXBundle.message("dialog.server.select.title")
    override fun dialogMessage(fileCount: Int): String = DeployXBundle.message("dialog.server.select.messageWithCount", fileCount)

    override fun buildItems(
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        targetServer: ServerConfig,
        selection: ServerSelectionResult
    ): List<UploadItem> = resolvedByFile.mapNotNull { (file, resolvedMappings) ->
        val resolved = ActionUtils.pickResolvedByServer(resolvedMappings, targetServer.id, selection.mappingId)
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
            excludePatterns = mapping.exclude
        )
    }

    override fun executeBatch(panel: FileSyncToolWindowPanel, items: List<UploadItem>) {
        panel.executePreviewBatch(items)
    }

    override fun actionText(): String = DeployXBundle.message("action.previewSync.text")
    override fun actionDescription(): String = DeployXBundle.message("action.previewSync.description")
}
