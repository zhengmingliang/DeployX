package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/** 打开 DeployX 脚本库。 */
class OpenScriptLibraryAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow("DeployX")?.show {
            val panel = FileSyncToolWindowPanel.getPanel(project)
            if (panel != null) {
                panel.selectScriptTab()
            } else {
                showNotification(project, DeployXBundle.message("notification.toolWindowNotInitialized"), NotificationType.WARNING)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = DeployXBundle.message("action.openScriptLibrary.text")
        e.presentation.description = DeployXBundle.message("action.openScriptLibrary.description")
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }
}
