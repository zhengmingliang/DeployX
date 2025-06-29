package com.alianga.idea.filesync.action

import com.alianga.idea.filesync.model.UploadItem
import com.alianga.idea.filesync.service.MappingManager
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
 * 预览同步 Action - 使用与实际 upload-only 相同的 --files-from dry-run 逻辑
 */
class PreviewSyncAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = getSelectedFiles(e)
        if (files.isEmpty()) return

        val mappingManager = MappingManager.getInstance()
        val previewItems = files.mapNotNull { file ->
            val resolved = mappingManager.resolveMappingByLocalPath(file.path, file.isDirectory)
                ?: return@mapNotNull null
            val mapping = resolved.mapping
            UploadItem(
                localPath = file.path,
                isDirectory = file.isDirectory,
                serverId = mapping.serverId,
                mappingId = mapping.effectiveId,
                sourceBaseDir = mapping.localDir,
                remoteBaseDir = mapping.remoteDir,
                relativePath = resolved.relativePath,
                excludePatterns = mapping.exclude
            )
        }

        if (previewItems.isEmpty()) {
            showNotification(project, "未找到匹配的映射，请先在设置中配置目录映射", NotificationType.WARNING)
            return
        }

        ToolWindowManager.getInstance(project).getToolWindow("File Sync")?.show()

        val panel = FileSyncToolWindowPanel.getPanel(project)
        if (panel != null) {
            if (previewItems.size < files.size) panel.appendLog("[WARN] 有 ${files.size - previewItems.size} 个文件未匹配到映射，已跳过")
            panel.executePreviewBatch(previewItems)
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
