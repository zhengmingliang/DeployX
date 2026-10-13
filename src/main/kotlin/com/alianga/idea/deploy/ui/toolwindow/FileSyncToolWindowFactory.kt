package com.alianga.idea.deploy.ui.toolwindow

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * 文件同步工具窗口工厂
 *
 * 1.1.0：不再自绘胶囊 Tab，「操作 / 日志 / 历史 / 脚本」改为平台内容页签（ContentManager），
 * 新 UI 放进标题栏、经典 UI 在标题下方，选中态与键盘行为由平台负责；设置进标题栏 gear 菜单。
 */
class FileSyncToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FileSyncToolWindowPanel(project)
        val cm = toolWindow.contentManager
        val contents = panel.pageComponents().zip(panel.pageTitles()).map { (comp, title) ->
            ContentFactory.getInstance().createContent(comp, title, false).apply { isCloseable = false }
        }
        contents.forEach { cm.addContent(it) }
        // 主面板（状态宿主）随第一个页签释放
        contents.firstOrNull()?.let { Disposer.register(it, panel) }
        panel.tabSelector = { i -> contents.getOrNull(i)?.let { cm.setSelectedContent(it, true) } }
        panel.tabTitleUpdater = { contents.zip(panel.pageTitles()).forEach { (c, t) -> c.displayName = t } }
        cm.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.operation == ContentManagerEvent.ContentOperation.add) {
                    panel.onPageSelected(contents.indexOf(event.content))
                }
            }
        })
        panel.settingsAction?.let { toolWindow.setAdditionalGearActions(DefaultActionGroup(it)) }
    }
}
