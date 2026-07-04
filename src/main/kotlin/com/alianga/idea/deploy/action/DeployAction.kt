package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.DeployItem
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 完整部署 Action - 右键菜单 "Deploy (Backup + Upload + Unzip)"
 * 支持多文件/目录按分组完整部署：pre/backup/upload/unzip/post 每组执行一次。
 */
class DeployAction : AnAction() {

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
            showNotification(project, DeployXBundle.message("notification.noMappingFound"), NotificationType.WARNING)
            return
        }

        val availableServers = resolvedByFile.values.flatten()
            .map { it.mapping.serverId }
            .distinct()
            .mapNotNull { ServerManager.getInstance().getServer(it) }

        val serverSelectionDialog = ServerSelectionDialog(
            availableServers,
            DeployXBundle.message("dialog.server.select.title.deploy"),
            DeployXBundle.message("dialog.server.select.message.deploy", files.size)
        )
        if (!serverSelectionDialog.showAndGet()) return
        val targetServer = serverSelectionDialog.selectedServer ?: return

        ToolWindowManager.getInstance(project).getToolWindow("DeployX")?.show()

        val items = resolvedByFile.mapNotNull { (file, resolvedMappings) ->
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

        val skipped = files.size - items.size
        val panel = FileSyncToolWindowPanel.getPanel(project)
        if (panel != null) {
            if (skipped > 0) panel.appendLog(DeployXBundle.message("toolwindow.log.skippedNoMapping", skipped, targetServer.id))
            panel.executeDeployBatch(items)
        } else {
            showNotification(project, DeployXBundle.message("notification.toolWindowNotOpen"), NotificationType.WARNING)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = DeployXBundle.message("action.deploy.text")
        e.presentation.description = DeployXBundle.message("action.deploy.description")
        e.presentation.isEnabledAndVisible = getSelectedFiles(e).isNotEmpty()
    }

    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> {
        return e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()
    }

    private fun showNotification(project: com.intellij.openapi.project.Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("DeployX").createNotification(content, type).notify(project)
    }
}
