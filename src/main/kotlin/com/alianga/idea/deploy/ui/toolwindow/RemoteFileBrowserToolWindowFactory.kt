package com.alianga.idea.deploy.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 远程文件浏览器工具窗口工厂。
 *
 * 在 IDE 侧边栏注册「DeployX Remote」工具窗口，承载 [RemoteFileBrowserPanel]，
 * 使远程文件浏览为非模态（不影响本地项目编辑）。
 *
 * 工具窗口隐藏时释放面板持有的 SSH 会话，显示时按需重建。
 */
class RemoteFileBrowserToolWindowFactory : ToolWindowFactory {

    companion object {
        const val TOOL_WINDOW_ID = "DeployX Remote"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RemoteFileBrowserPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer { panel.dispose() }
        toolWindow.contentManager.addContent(content)
    }
}
