package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.util.ui.JBUI
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.SwingConstants

/**
 * 按钮与 Action 工厂 - 从 FileSyncToolWindowPanel 抽取的无状态工厂方法集合。
 *
 * 统一工具栏 Action、操作面板按钮、紧凑工具窗口按钮的创建方式，
 * 便于在多个面板间复用，保持视觉与交互一致。
 */
object UiButtonFactory {

    fun createAction(text: String, icon: Icon, handler: () -> Unit): AnAction {
        return object : AnAction(text, text, icon) {
            override fun actionPerformed(e: AnActionEvent) { handler() }
        }
    }

    /**
     * 创建工具栏 Action，文案通过 bundle key 在 [update] 中动态获取，
     * 使语言切换后无需重建即可刷新显示文本。
     *
     * @param textKey 按钮文案（菜单/标签用）的 bundle key
     * @param descriptionKey 悬停说明（tooltip）的 bundle key；为 null 时与文案相同
     */
    fun createLocalizedAction(textKey: String, icon: Icon, descriptionKey: String? = null, handler: () -> Unit): AnAction {
        val descKey = descriptionKey ?: textKey
        return object : AnAction(DeployXBundle.message(textKey), DeployXBundle.message(descKey), icon) {
            override fun actionPerformed(e: AnActionEvent) { handler() }
            override fun update(e: AnActionEvent) {
                e.presentation.text = DeployXBundle.message(textKey)
                e.presentation.description = DeployXBundle.message(descKey)
            }
        }
    }

    fun createActionButton(text: String, icon: Icon, handler: () -> Unit): JButton {
        return JButton(text, icon).apply { addActionListener { handler() } }
    }

    fun createIconButton(toolTip: String, icon: Icon, handler: () -> Unit): JButton {
        return JButton(icon).apply {
            this.toolTipText = toolTip
            isFocusable = false
            addActionListener { handler() }
        }
    }

    /**
     * 创建紧凑的工具窗口按钮（无边框，小尺寸）
     * 同时显示图标与文字，并附带 toolTipText 作为补充说明，避免仅有图标时含义不清。
     */
    fun createToolWindowButton(text: String, icon: Icon, toolTip: String? = null): JButton {
        return JButton(text, icon).apply {
            isFocusable = false
            isBorderPainted = false
            isContentAreaFilled = false
            margin = JBUI.insets(2, 2, 2, 2)
            putClientProperty("JButton.buttonType", "square")
            if (!toolTip.isNullOrBlank()) {
                this.toolTipText = toolTip
            }
            // 水平排列：图标在左，文字在右，确保文字始终可见；缩小图标与文字间距使按钮更紧凑
            horizontalTextPosition = SwingConstants.TRAILING
            horizontalAlignment = SwingConstants.LEFT
            iconTextGap = 2
        }
    }
}
