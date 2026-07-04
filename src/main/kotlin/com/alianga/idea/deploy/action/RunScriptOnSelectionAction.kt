package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ui.dialog.ScriptRunDialog
import com.alianga.idea.deploy.ui.dialog.ScriptPickerDialog
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 从当前选中文件构建上下文并运行脚本。
 */
class RunScriptOnSelectionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = getSelectedFiles(e)
        if (files.isEmpty()) return

        val context = buildContext(project, files)
        ToolWindowManager.getInstance(project).getToolWindow("DeployX")?.show {
            val panel = FileSyncToolWindowPanel.getPanel(project)
            panel?.selectScriptTab()
            val picker = ScriptPickerDialog(project, context)
            if (picker.showAndGet()) {
                val script = picker.getScript() ?: return@show
                val runDialog = ScriptRunDialog(project, script, context) { line ->
                    val serverId = script.serverId.ifBlank { context.server?.id ?: context.mapping?.serverId ?: "" }
                    panel?.appendLog(serverId, line)
                }
                runDialog.show()
            }
        } ?: showNotification(project, DeployXBundle.message("notification.cannotOpenToolWindow"), NotificationType.WARNING)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = DeployXBundle.message("action.runScriptOnSelection.text")
        e.presentation.description = DeployXBundle.message("action.runScriptOnSelection.description")
        e.presentation.isEnabledAndVisible = e.project != null && getSelectedFiles(e).isNotEmpty()
    }

    private fun buildContext(project: Project, files: Array<VirtualFile>): ScriptRunContext {
        val paths = files.map { it.path }
        val first = files.firstOrNull()
        val resolved = first?.let { MappingManager.getInstance().resolveMappingByLocalPath(it.path, it.isDirectory) }
        val server = resolved?.mapping?.serverId?.let { ServerManager.getInstance().getServer(it) }
        return ScriptRunContext(
            server = server,
            mapping = resolved?.mapping,
            remoteDir = resolved?.resolvedRemoteDir,
            localSelectedPaths = paths,
            projectBasePath = project.basePath
        )
    }

    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }
}
