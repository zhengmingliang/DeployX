package com.alianga.idea.deploy.ui

import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.KeyStroke

/**
 * 内置浏览按钮的路径输入框（平台规范 Built-in button）。
 *
 * 取代原来输入框右侧独立的「…」按钮：图标嵌在输入框内，不再多占一列；
 * 快捷键 Shift+Enter 与点击图标等价。本地 / 远程用不同图标区分语义。
 *
 * @author 郑明亮
 */
class PathField(icon: Icon, tooltip: String) : ExtendableTextField() {

    /** 浏览回调；点击内置图标或按 Shift+Enter 时触发 */
    var onBrowse: (() -> Unit)? = null

    private var extension: ExtendableTextComponent.Extension = createExtension(icon, tooltip)

    init {
        addExtension(extension)
        val key = "deployx.browse"
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), key)
        actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { onBrowse?.invoke() }
        })
    }

    private fun createExtension(icon: Icon, tooltip: String) =
        ExtendableTextComponent.Extension.create(icon, tooltip) { onBrowse?.invoke() }

    /** 语言切换时刷新内置按钮 tooltip */
    fun updateBrowseTooltip(icon: Icon, tooltip: String) {
        removeExtension(extension)
        extension = createExtension(icon, tooltip)
        addExtension(extension)
        accessibleContext.accessibleDescription = tooltip
    }
}
