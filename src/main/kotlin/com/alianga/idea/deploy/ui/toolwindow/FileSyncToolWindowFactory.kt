package com.alianga.idea.deploy.ui.toolwindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 文件同步工具窗口工厂
 *
 * 1.1.1：只建一个 Content，页签行由面板自绘（PageTabBar），窄宽下页签永不被平台折叠；
 * 设置按钮放到标题栏，一次点击直接打开（同时保留 gear 菜单入口）。
 */
class FileSyncToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FileSyncToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false).apply { isCloseable = false }
        toolWindow.contentManager.addContent(content)
        Disposer.register(content, panel)
        panel.settingsAction?.let {
            toolWindow.setTitleActions(listOf(it))
            toolWindow.setAdditionalGearActions(DefaultActionGroup(it))
        }
    }
}
