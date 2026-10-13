package com.alianga.idea.deploy.ui

import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 分组 = 「标题 + 分隔线」（平台规范 Group header），取代 TitledBorder 方框。
 *
 * TitledBorder 嵌套边框会吃掉横向空间且边框色取自 LAF 默认值、主题下不稳定；
 * [TitledSeparator] 是平台原生组件，颜色随主题。内容区左缩进与平台 DSL group 一致。
 *
 * @author 郑明亮
 */
class GroupHeader(title: String, content: JComponent) : JPanel(BorderLayout()) {
    private val separator = TitledSeparator(title)

    init {
        isOpaque = false
        add(separator, BorderLayout.NORTH)
        content.border = JBUI.Borders.merge(content.border, JBUI.Borders.empty(4, 0, 0, 0), true)
        add(content, BorderLayout.CENTER)
        alignmentX = LEFT_ALIGNMENT
    }

    var title: String
        get() = separator.text
        set(value) { separator.text = value }
}
