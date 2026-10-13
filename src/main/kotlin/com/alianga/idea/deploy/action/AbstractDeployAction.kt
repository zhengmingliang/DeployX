package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ui.dialog.ServerSelectionDialog
import com.alianga.idea.deploy.ui.toolwindow.FileSyncToolWindowPanel
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 部署/同步类 Action 的模板基类
 *
 * 封装三个 Action 共有的流程：
 * 1. 获取选中的文件/目录
 * 2. 解析每个文件对应的映射
 * 3. 收集可用服务器
 * 4. 弹出服务器选择对话框（1.0.8：同一服务器多条嵌套映射竞争时可手动选择映射）
 * 5. 显示工具窗口
 * 6. 子类构造执行项并调用工具窗口执行批处理
 *
 * 子类只需实现几个抽象方法即可，避免重复的样板代码。
 *
 * @param T 执行项类型（DeployItem / UploadItem）
 */
abstract class AbstractDeployAction<T> : AnAction() {

    /**
     * 服务器选择结果
     *
     * @param mappingId 1.0.8：用户在该服务器上手动选择的映射 ID（effectiveId）；
     *   null 表示自动选择最深层级（子目录）映射
     */
    data class ServerSelectionResult(
        val server: ServerConfig,
        val executePreCommand: Boolean,
        val executePostCommand: Boolean,
        val mappingId: String? = null
    )

    /**
     * 多服务器选择结果（支持并行部署）
     *
     * @param mappingIdsByServerId 1.0.8：每台服务器最终使用的映射 ID
     *   （serverId -> mapping effectiveId），无歧义的服务器也在其中（值为其唯一/默认映射）
     */
    data class MultiServerSelectionResult(
        val servers: List<ServerConfig>,
        val executePreCommand: Boolean,
        val executePostCommand: Boolean,
        val mappingIdsByServerId: Map<String, String> = emptyMap()
    )

    /** 1.1.0：被隐藏（无匹配映射）的服务器数量，供对话框提示 */
    protected var hiddenServerCount: Int = 0

    /** 1.1.0：对话框确认按钮的具体动词（不再用通用「OK」） */
    protected open fun okText(): String = DeployXBundle.message("dialog.server.select.ok.deploy")

    final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    final override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = ActionUtils.getSelectedFiles(e)
        if (files.isEmpty()) return

        val mappingManager = MappingManager.getInstance()
        val resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>> =
            files.associateWith { file ->
                mappingManager.resolveMappingsByLocalPath(file.path, file.isDirectory)
            }.filterValues { it.isNotEmpty() }

        if (resolvedByFile.isEmpty()) {
            ActionUtils.showNoMappingNotification(project, DeployXBundle.message("notification.noMappingFound"))
            return
        }

        // 1.1.0：只列出覆盖全部选中项的服务器（多选取交集）；没有匹配映射的服务器不显示，
        // 对话框底部提示「已隐藏 N 台」（实施方案 3.8）
        val coveringIds = com.alianga.idea.deploy.ui.TargetCandidates.coveringAll(
            resolvedByFile.values.map { list -> list.map { it.mapping.serverId } }
        )
        val availableServers = coveringIds.mapNotNull { ServerManager.getInstance().getServer(it) }
        hiddenServerCount = (ServerManager.getInstance().getServers().size - availableServers.size).coerceAtLeast(0)
        if (availableServers.isEmpty()) {
            ActionUtils.showNoMappingNotification(project, DeployXBundle.message("notification.noCommonMapping"))
            return
        }

        val multiSelection = selectServers(availableServers, resolvedByFile, files.size) ?: return

        ToolWindowManager.getInstance(project).getToolWindow("DeployX")?.show()

        // 遍历每个选中的服务器，构建执行项
        val items = multiSelection.servers.flatMap { targetServer ->
            buildItems(resolvedByFile, targetServer, ServerSelectionResult(
                server = targetServer,
                executePreCommand = multiSelection.executePreCommand,
                executePostCommand = multiSelection.executePostCommand,
                // 1.0.8：传入用户在该服务器上手动选择的映射（null = 自动最深层级）
                mappingId = multiSelection.mappingIdsByServerId[targetServer.id]
            ))
        }
        val skipped = files.size - items.size
        val panel = FileSyncToolWindowPanel.getPanel(project)
        if (panel != null) {
            if (skipped > 0) {
                panel.appendLog(
                    DeployXBundle.message("toolwindow.log.skippedNoMapping", skipped, multiSelection.servers.joinToString { it.id })
                )
            }
            executeBatch(panel, items)
        } else {
            ActionUtils.showNotification(
                project,
                DeployXBundle.message("notification.toolWindowNotOpen"),
                NotificationType.WARNING
            )
        }
    }

    /**
     * 选择目标服务器（默认实现：弹出对话框）
     *
     * 子类可重写以实现"单服务器跳过对话框"等定制逻辑（参考 QuickPushAction）。
     */
    protected open fun selectServer(
        servers: List<ServerConfig>,
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        fileCount: Int
    ): ServerSelectionResult? {
        if (servers.isEmpty()) return null
        val commandAvailability = ActionUtils.buildCommandAvailability(resolvedByFile.values.flatten())
        val dialog = ServerSelectionDialog(
            servers,
            dialogTitle(),
            dialogMessage(fileCount),
            showCommandOptions = showCommandOptions(),
            commandAvailabilityByServerId = commandAvailability,
            // 1.0.8：嵌套映射歧义时在对话框内提供映射选择
            resolvedByServerId = ActionUtils.buildResolvedByServerId(resolvedByFile),
            okText = okText(),
            hiddenServerCount = hiddenServerCount
        )
        if (!dialog.showAndGet()) return null
        val server = dialog.selectedServer ?: return null
        return ServerSelectionResult(
            server = server,
            executePreCommand = dialog.executePreCommand,
            executePostCommand = dialog.executePostCommand,
            mappingId = dialog.selectedMappingId
        )
    }

    /**
     * 选择目标服务器（多选模式，支持并行部署）。
     *
     * 默认实现弹出多选对话框；QuickPushAction 等可重写以跳过对话框。
     */
    protected open fun selectServers(
        servers: List<ServerConfig>,
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        fileCount: Int
    ): MultiServerSelectionResult? {
        if (servers.isEmpty()) return null
        val commandAvailability = ActionUtils.buildCommandAvailability(resolvedByFile.values.flatten())
        val dialog = ServerSelectionDialog(
            servers,
            dialogTitle(),
            dialogMessage(fileCount),
            showCommandOptions = showCommandOptions(),
            commandAvailabilityByServerId = commandAvailability,
            // 1.0.8：嵌套映射歧义时在对话框内提供映射选择
            resolvedByServerId = ActionUtils.buildResolvedByServerId(resolvedByFile),
            okText = okText(),
            hiddenServerCount = hiddenServerCount
        )
        if (!dialog.showAndGet()) return null
        val selected = dialog.selectedServers.ifEmpty { listOfNotNull(dialog.selectedServer) }
        if (selected.isEmpty()) return null
        return MultiServerSelectionResult(
            selected,
            dialog.executePreCommand,
            dialog.executePostCommand,
            mappingIdsByServerId = dialog.selectedMappingIdsByServerId
        )
    }

    /**
     * 是否在服务器选择对话框中显示前/后命令勾选项（默认不显示）
     */
    protected open fun showCommandOptions(): Boolean = false

    /** 服务器选择对话框标题 */
    protected abstract fun dialogTitle(): String

    /** 服务器选择对话框消息文案（fileCount 为选中文件数） */
    protected abstract fun dialogMessage(fileCount: Int): String

    /** 根据选中文件、目标服务器和选择结果构造执行项列表 */
    protected abstract fun buildItems(
        resolvedByFile: Map<VirtualFile, List<MappingManager.ResolvedMapping>>,
        targetServer: ServerConfig,
        selection: ServerSelectionResult
    ): List<T>

    /** 在工具窗口面板上执行批处理 */
    protected abstract fun executeBatch(panel: FileSyncToolWindowPanel, items: List<T>)

    final override fun update(e: AnActionEvent) {
        e.presentation.text = actionText()
        e.presentation.description = actionDescription()
        e.presentation.isEnabledAndVisible = ActionUtils.getSelectedFiles(e).isNotEmpty()
    }

    /** Action 在菜单中显示的文本 */
    protected abstract fun actionText(): String

    /** Action 的描述 */
    protected abstract fun actionDescription(): String
}
