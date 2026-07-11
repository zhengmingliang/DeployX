package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

/**
 * 按 Alt+Shift+Z 呼出 DeployX 快捷菜单。
 *
 * 弹出列表展示所有 DeployX 核心操作：部署、同步到服务器、快速推送、预览同步、打开 SSH 终端。
 * 选择后立即执行对应 Action。
 */
class ShowDeployXMenuAction : AnAction() {

    companion object {
        /** 菜单中展示的 action id 列表 */
        private val ACTION_IDS = listOf(
            "DeployX.Deploy",
            "DeployX.SyncFile",
            "DeployX.PullFromServer",
            "DeployX.QuickPush",
            "DeployX.PreviewSync",
            "DeployX.OpenSshTerminal",
        )
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project ?: return
        val actionManager = ActionManager.getInstance()

        // 加载所有菜单条目，并过滤掉不可用的
        val items = ACTION_IDS.mapNotNull { id ->
            val action = actionManager.getAction(id) ?: return@mapNotNull null
            val presentation = action.templatePresentation.clone()
            // 使用兼容的方式更新 action 状态：优先调用新版 API，降级到旧版
            val dummyEvent = AnActionEvent.createFromAnAction(action, null, "ShowDeployXMenuAction", e.dataContext)
            try {
                // 新版 API (2024.1+)
                ActionUtil::class.java.getMethod("updateAction", AnAction::class.java, AnActionEvent::class.java)
                    .invoke(null, action, dummyEvent)
            } catch (e: Exception) {
                // 降级到旧版 API (2023.x 及之前)
                @Suppress("DEPRECATION")
                ActionUtil.performDumbAwareUpdate(action, dummyEvent, false)
            }
            if (!dummyEvent.presentation.isEnabledAndVisible) return@mapNotNull null
            MenuItem(id, action, dummyEvent.presentation)
        }

        if (items.isEmpty()) return

        showPopup(e.dataContext, items)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun showPopup(dataContext: DataContext, items: List<MenuItem>) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(DeployXBundle.message("action.showMenu.title"))
            .setMovable(true)
            .setRenderer(MenuRenderer())
            .setItemChosenCallback { item ->
                val actionManager = ActionManager.getInstance()
                val action = actionManager.getAction(item.id) ?: return@setItemChosenCallback
                // 使用兼容的方式执行 action
                try {
                    // 新版 API (2024.1+): performAction(action, event)
                    val event = AnActionEvent.createFromAnAction(action, null, "ShowDeployXMenuAction", dataContext)
                    ActionUtil::class.java.getMethod("performAction", AnAction::class.java, AnActionEvent::class.java)
                        .invoke(null, action, event)
                } catch (e: Exception) {
                    // 降级到旧版 API (2023.x 及之前)
                    @Suppress("DEPRECATION")
                    ActionUtil.invokeAction(action, dataContext, "ShowDeployXMenuAction", null, null)
                }
            }
            .createPopup()
        // 基于本次 action 的 dataContext 定位弹窗位置，而非"当前键盘焦点"。
        // 多 IDEA 项目窗口时，showInFocusCenter() 会取 KeyboardFocusManager 的 focusOwner，
        // 它可能还停留在另一个窗口的组件上，导致菜单弹到错误的窗口。
        // guessBestPopupLocation 从 dataContext 取 CONTEXT_COMPONENT 来定位窗口，与触发
        // 该快捷键的窗口保持一致。
        popup.show(JBPopupFactory.getInstance().guessBestPopupLocation(dataContext))
    }

    /** 菜单项数据 */
    private data class MenuItem(
        val id: String,
        val action: AnAction,
        val presentation: Presentation,
    )

    /** 菜单列表渲染器：显示图标 + 文本 + 快捷键（如有） */
    private class MenuRenderer : ColoredListCellRenderer<MenuItem>() {
        override fun customizeCellRenderer(
            list: JList<out MenuItem>,
            item: MenuItem,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            item.presentation.icon?.let { icon = it }
            append(item.presentation.text ?: item.id)
        }
    }
}
