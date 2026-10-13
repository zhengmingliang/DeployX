package com.alianga.idea.deploy.ui

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * 底部操作栏布局：放得下时「拉取 ▾」靠左、「部署 ▾」靠右；
 * 放不下时（窄档）主按钮「部署 ▾」占满第一行、「拉取 ▾」占满第二行，避免 BorderLayout 下两按钮重叠或被裁掉。
 *
 * @author 郑明亮
 */
class ActionBarLayout(private val left: Component, private val right: Component, private val gap: Int) : LayoutManager {

    /** 是否需要堆叠（纯函数，便于单测） */
    companion object {
        fun stacked(available: Int, leftWidth: Int, rightWidth: Int, gap: Int): Boolean = leftWidth + gap + rightWidth > available
    }

    override fun layoutContainer(parent: Container) {
        val ins = parent.insets
        val w = parent.width - ins.left - ins.right
        val l = left.preferredSize
        val r = right.preferredSize
        if (!stacked(w, l.width, r.width, gap)) {
            val h = maxOf(l.height, r.height)
            left.setBounds(ins.left, ins.top, l.width, h)
            right.setBounds(ins.left + w - r.width, ins.top, r.width, h)
        } else {
            right.setBounds(ins.left, ins.top, w, r.height)
            left.setBounds(ins.left, ins.top + r.height + gap, w, l.height)
        }
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val ins = parent.insets
        val l = left.preferredSize
        val r = right.preferredSize
        val w = parent.width - ins.left - ins.right
        val h = if (w > 0 && stacked(w, l.width, r.width, gap)) l.height + gap + r.height else maxOf(l.height, r.height)
        return Dimension(l.width + gap + r.width + ins.left + ins.right, h + ins.top + ins.bottom)
    }

    /** 最小宽度取较宽的单个按钮，让父容器可以把我们挤窄而不是撑宽整页 */
    override fun minimumLayoutSize(parent: Container): Dimension {
        val ins = parent.insets
        return Dimension(maxOf(left.minimumSize.width, right.minimumSize.width) + ins.left + ins.right,
            left.preferredSize.height + gap + right.preferredSize.height + ins.top + ins.bottom)
    }

    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}
}
