package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.DeployItem
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile

/**
 * 完整部署 Action - 右键菜单 "Deploy (Backup + Upload + Unzip)"
 *
 * 支持多文件/目录按分组完整部署：pre/backup/upload/unzip/post 每组执行一次。
 * 命令执行完全按映射配置，不依赖用户在对话框勾选。
 */
class DeployAction : AbstractDeployAction<DeployItem>() {

    override fun dialogTitle(): String =
        DeployXBundle.message("dialog.server.select.title.deploy")

    override fun dialogMessage(fileCount: Int): String =
        DeployXBundle.message("dialog.server.select.message.deploy", fileCount)

    override fun showCommandOptions(): Boolean = false

    override fun buildItems(
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        targetServer: ServerConfig,
        selection: ServerSelectionResult
    ): List<DeployItem> {
        // Deploy 模式下，命令执行完全按映射配置，不依赖 selection.executePreCommand/Post
        return resolvedByFile.mapNotNull { (file, resolvedMappings) ->
            val resolved = resolvedMappings.firstOrNull { it.mapping.serverId == targetServer.id }
                ?: return@mapNotNull null
            val mapping = resolved.mapping
            DeployItem(
                localPath = file.path,
                isDirectory = file.isDirectory,
                serverId = targetServer.id,
                mappingId = mapping.effectiveId,
                sourceBaseDir = mapping.localDir,
                remoteBaseDir = mapping.remoteDir,
                relativePath = resolved.relativePath,
                excludePatterns = mapping.exclude,
                backupDir = if (mapping.backupEnabled) mapping.backupDir.ifBlank { null } else null,
                backupSource = if (mapping.backupEnabled) mapping.backupSource.ifBlank { null } else null,
                unzipDest = if (mapping.unzipEnabled) mapping.unzipDest.ifBlank { null } else null,
                preCommand = if (mapping.effectivePreCommandEnabled) mapping.preCommand.ifBlank { null } else null,
                postCommand = if (mapping.effectivePostCommandEnabled) mapping.postCommand.ifBlank { null } else null
            )
        }
    }

    override fun executeBatch(panel: FileSyncToolWindowPanel, items: List<DeployItem>) {
        panel.executeDeployBatch(items)
    }

    override fun actionText(): String = DeployXBundle.message("action.deploy.text")
    override fun actionDescription(): String = DeployXBundle.message("action.deploy.description")
}
