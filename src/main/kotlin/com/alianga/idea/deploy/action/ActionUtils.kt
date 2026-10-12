package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.alianga.idea.deploy.util.WindowsToastNotifier
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
     * 1. **OS 系统通知**（仅当 IDE 窗口未激活时）：
     *    - **Windows**：走 [WindowsToastNotifier]（PowerShell 调 WinRT Toast），稳定显示在
     *      Windows 10/11 通知中心。平台内置的 [SystemNotifications] 在 Windows 上走
     *      `java.awt.TrayIcon` 经典托盘气泡，已废弃且不可靠（`SystemTray.isSupported()`
     *      返回 false 或 AWTException 被吞掉时静默失败），故 Windows 不走平台 API。
     *    - **Linux**：走平台 [SystemNotifications]（经 JNA 调 libnotify，通知中心）。
     *    - **macOS**：走平台 [SystemNotifications]（通知中心）。
     *    图标由平台/系统解析，绕过 SVG 无法被 ImageIcon 加载的问题。
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

        // 1) OS 系统通知中心（仅在 IDE 窗口未激活时弹出）
        if (!ApplicationManager.getApplication().isActive) {
            val plainMessage = StringUtil.stripHtml(message, true)
            if (WindowsToastNotifier.isAvailable) {
                // Windows：PowerShell Toast（稳定，显示在通知中心）
                WindowsToastNotifier.notify(title, plainMessage)
            } else {
                // Linux/macOS：平台 SystemNotifications（Linux 走 libnotify，macOS 走通知中心）
                try {
                    SystemNotifications.getInstance().notify("DeployX", title, plainMessage)
                } catch (_: Throwable) {
                    // 平台 OS 通知不可用，忽略；下面仍会走 Balloon
                }
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

    /**
     * 1.0.8 新增：在同一服务器的多匹配中挑出**最长 `localDir` 前缀**（最具体）的解析项。
     *
     * [AbstractDeployAction] 用 `resolveMappingsByLocalPath` 拿到的是一个路径下所有匹配的映射
     * 列表（外层、内层、共享同一 `serverId` 的多个映射都会进入），按 `firstOrNull { serverId == ... }`
     * 简单取第一个会选中**插入顺序中最先注册**的那一个（往往是外层、最宽的），导致内层映射永远走不到。
     *
     * 1.0.8 起改为委托 [MappingManager.pickResolvedByServer]，行为不变（等价于
     * `preferredMappingId = null`），保留此方法供旧调用方使用。
     */
    fun pickMostSpecificByServer(
        resolvedMappings: List<MappingManager.ResolvedMapping>,
        serverId: String
    ): MappingManager.ResolvedMapping? =
        MappingManager.pickResolvedByServer(resolvedMappings, serverId)

    /**
     * 1.0.8 新增：在同一服务器的多匹配中挑选最终使用的映射。
     *
     * - [preferredMappingId] 非空且命中：使用用户手动选择的映射（父目录/次优先级映射）
     * - 否则：回退到最长 `localDir` 前缀（最具体/最深层级），与 1.0.8 默认行为一致
     *
     * @param resolvedMappings [MappingManager.resolveMappingsByLocalPath] 的返回列表
     * @param serverId 目标服务器 ID
     * @param preferredMappingId 用户在映射选择对话框中选中的映射 ID（null = 自动）
     */
    fun pickResolvedByServer(
        resolvedMappings: List<MappingManager.ResolvedMapping>,
        serverId: String,
        preferredMappingId: String? = null
    ): MappingManager.ResolvedMapping? =
        MappingManager.pickResolvedByServer(resolvedMappings, serverId, preferredMappingId)

    /**
     * 1.0.8 新增：把「文件 -> 解析映射列表」按服务器归并为「服务器 -> 候选映射列表」。
     *
     * 同一映射可能因多个选中文件重复出现，按 `effectiveId` 去重（保持首次出现的顺序），
     * 供服务器选择对话框展示每台服务器的映射候选。
     */
    fun buildResolvedByServerId(
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>
    ): Map<String, List<MappingManager.ResolvedMapping>> {
        val result = linkedMapOf<String, MutableList<MappingManager.ResolvedMapping>>()
        resolvedByFile.values.forEach { resolvedList ->
            resolvedList.forEach { resolved ->
                val bucket = result.getOrPut(resolved.mapping.serverId) { mutableListOf() }
                if (bucket.none { it.mapping.effectiveId == resolved.mapping.effectiveId }) {
                    bucket.add(resolved)
                }
            }
        }
        return result
    }

    /**
     * 1.0.8 新增：是否存在「同一服务器多条映射竞争」的歧义（嵌套父子目录映射场景）。
     *
     * 返回 true 时，部署/同步流程应向用户提供映射选择入口（对话框内映射面板或独立映射选择对话框）。
     */
    fun hasMappingChoices(resolvedByServerId: Map<String, List<MappingManager.ResolvedMapping>>): Boolean =
        resolvedByServerId.values.any { it.size > 1 }
}

