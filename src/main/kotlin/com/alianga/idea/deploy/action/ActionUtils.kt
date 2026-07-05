package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Action 公共工具方法
 *
 * 提取 DeployAction / SyncFileAction / QuickPushAction 共用的辅助逻辑，避免代码重复。
 */
object ActionUtils {

    /**
     * 获取当前事件选中的文件/目录数组
     */
    fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()

    /**
     * 显示 DeployX 通知
     */
    fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }

    /**
     * 根据已解析的映射列表，按服务器分组构建每个服务器的前/后命令可用性
     * （用于 ServerSelectionDialog 的命令选项勾选状态）
     */
    fun buildCommandAvailability(
        resolvedMappings: List<MappingManager.ResolvedMapping>
    ): Map<String, ServerSelectionDialog.CommandAvailability> {
        return resolvedMappings.groupBy { it.mapping.serverId }.mapValues { (_, values) ->
            ServerSelectionDialog.CommandAvailability(
                hasPreCommand = values.any {
                    it.mapping.effectivePreCommandEnabled && it.mapping.preCommand.isNotBlank()
                },
                hasPostCommand = values.any {
                    it.mapping.effectivePostCommandEnabled && it.mapping.postCommand.isNotBlank()
                }
            )
        }
    }
}
