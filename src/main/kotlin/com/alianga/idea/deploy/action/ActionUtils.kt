package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.swing.ImageIcon

/**
 * Action 公共工具方法
 *
 * 提取 DeployAction / SyncFileAction / QuickPushAction 共用的辅助逻辑，避免代码重复。
 */
object ActionUtils {

    /** 系统托盘图标（惰性初始化，仅创建一次） */
    @Volatile
    private var trayIcon: TrayIcon? = null

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
     * 显示系统级通知。
     *
     * 优先使用 OS 系统托盘（java.awt.SystemTray），让用户在 IDE 窗口未聚焦时也能收到提醒；
     * 系统托盘不可用时回退到 IntelliJ Balloon 通知。
     *
     * 受 [FileSyncSettings.systemNotification] 开关控制：关闭时不弹任何通知。
     *
     * @param project 用于 Balloon 回退通知的 project 上下文
     * @param title 通知标题
     * @param message 通知内容
     * @param type 通知类型（决定图标）
     */
    fun showSystemNotification(
        project: Project?,
        title: String,
        message: String,
        type: NotificationType = NotificationType.INFORMATION
    ) {
        if (!FileSyncSettings.getInstance().systemNotification) return

        // 尝试系统托盘
        if (SystemTray.isSupported()) {
            try {
                val icon = getOrCreateTrayIcon() ?: return showBalloonNotification(project, message, type)
                val msgType = when (type) {
                    NotificationType.ERROR -> TrayIcon.MessageType.ERROR
                    NotificationType.WARNING -> TrayIcon.MessageType.WARNING
                    else -> TrayIcon.MessageType.INFO
                }
                icon.displayMessage(title, message, msgType)
                return
            } catch (_: Exception) {
                // 系统托盘异常，回退到 Balloon
            }
        }
        showBalloonNotification(project, message, type)
    }

    private fun showBalloonNotification(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }

    /**
     * 获取或创建系统托盘图标。
     * 使用插件的 toolWindow 图标，找不到时返回 null。
     */
    private fun getOrCreateTrayIcon(): TrayIcon? {
        trayIcon?.let { return it }
        return try {
            val url = ActionUtils::class.java.getResource("/icons/toolWindow.svg")
                ?: ActionUtils::class.java.getResource("/icons/toolWindow.png")
            val image = if (url != null) ImageIcon(url).image else null
            val icon = if (image != null) {
                TrayIcon(image, "DeployX")
            } else {
                // 无图标资源时用一个 1x1 透明像素（部分 OS 不允许 null image）
                val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                TrayIcon(img, "DeployX")
            }
            icon.isImageAutoSize = true
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            icon
        } catch (_: Exception) {
            null
        }
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
