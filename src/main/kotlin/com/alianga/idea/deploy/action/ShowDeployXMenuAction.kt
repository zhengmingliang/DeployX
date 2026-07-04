package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.Project
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
            "DeployX.QuickPush",
            "DeployX.PreviewSync",
            "DeployX.OpenSshTerminal",
        )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val actionManager = ActionManager.getInstance()

        // 加载所有菜单条目，并过滤掉不可用的
        val items = ACTION_IDS.mapNotNull { id ->
            val action = actionManager.getAction(id) ?: return@mapNotNull null
            val presentation = action.templatePresentation.clone()
            // 直接使用 AnActionEvent 来更新，这是兼容的方式
            val dummyEvent = AnActionEvent.createFromAnAction(action, null, "ShowDeployXMenuAction", e.dataContext)
            ActionUtil.performDumbAwareUpdate(action, dummyEvent, false)
            if (!dummyEvent.presentation.isEnabledAndVisible) return@mapNotNull null
            MenuItem(id, action, dummyEvent.presentation)
        }

        if (items.isEmpty()) return

        showPopup(project, e.dataContext, items)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private fun showPopup(project: Project, dataContext: DataContext, items: List<MenuItem>) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(DeployXBundle.message("action.showMenu.title"))
            .setMovable(true)
            .setRenderer(MenuRenderer())
            .setItemChosenCallback { item ->
                val actionManager = ActionManager.getInstance()
                val action = actionManager.getAction(item.id) ?: return@setItemChosenCallback
                ActionUtil.invokeAction(action, dataContext, "ShowDeployXMenuAction", null, null)
            }
            .createPopup()
        popup.showInFocusCenter()
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
