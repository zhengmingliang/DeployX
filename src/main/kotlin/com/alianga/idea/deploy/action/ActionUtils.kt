package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SystemNotifications

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
     * 显示 DeployX 通知（IDE 内 Balloon 通知）
     */
    fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }

    /**
     * 显示传输完成通知。
     *
     * 双通道策略，保证用户总能收到提醒：
     * 1. **OS 系统通知**（仅当 IDE 窗口未激活时）：通过 IntelliJ Platform 的
     *    [SystemNotifications] API 触发，Linux 走 libnotify（Cinnamon/GNOME 通知中心），
     *    macOS 走通知中心，Windows 走系统托盘通知。不依赖 java.awt.SystemTray，
     *    图标由平台解析为应用光栅图标，绕过 SVG 无法被 ImageIcon 加载的问题。
     *    平台仅在 IDE 未聚焦时才真正弹出 OS 通知（聚焦时由下方 Balloon 承担）。
     * 2. **IDE Balloon 通知**（始终）：IDE 右下角气泡，聚焦时可见。
     *
     * 受 [FileSyncSettings.systemNotification] 开关控制：关闭时不弹任何通知。
     *
     * @param project 用于 Balloon 通知的 project 上下文
     * @param title 通知标题（OS 通知用）
     * @param message 通知内容
     * @param type 通知类型（决定 Balloon 图标）
     */
    fun showSystemNotification(
        project: Project?,
        title: String,
        message: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        if (!FileSyncSettings.getInstance().systemNotification) return

        // 1) OS 系统通知中心（仅在 IDE 窗口未激活时由平台决定是否弹出）
        //    SystemNotifications 内部：Linux 经 JNA 调 libnotify，无 libnotify/JNA 时静默 no-op
        if (!ApplicationManager.getApplication().isActive) {
            try {
                SystemNotifications.getInstance()
                    .notify("DeployX", title, StringUtil.stripHtml(message, true))
            } catch (_: Throwable) {
                // 平台 OS 通知不可用，忽略；下面仍会走 Balloon
            }
        }

        // 2) 始终同时给一条 IDE 内 Balloon，保证 IDE 聚焦时也能看到
        NotificationGroupManager.getInstance()
            .getNotificationGroup("DeployX")
            .createNotification(title, message, type)
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

