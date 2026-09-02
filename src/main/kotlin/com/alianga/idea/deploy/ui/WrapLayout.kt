package com.alianga.idea.deploy.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets

/**
 * 支持自动换行的 [FlowLayout]。
 *
 * 原生 FlowLayout 的 preferredLayoutSize 始终按"单行"计算高度；面板放在
 * BoxLayout(Y_AXIS) 或滚动面板里时，换行产生的高度不会被计入首选尺寸，
 * 导致换到第二行的组件超出面板边界被裁剪。本布局按容器当前宽度模拟
 * FlowLayout 的换行逻辑计算真实的首选/最小尺寸（多行高度）。
 */
class WrapLayout(alignment: Int, hgap: Int, vgap: Int) : FlowLayout(alignment, hgap, vgap) {

    /** 上次 layoutContainer 计算出的首选尺寸，用于检测换行数变化 */
    private var lastPreferredSize: Dimension? = null

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, preferred = false)

    /** 所有可见子组件排成单行所需的最小宽度（含 insets 与首尾 hgap）。 */
    fun singleRowWidth(target: Container): Int {
        val insets: Insets = target.insets
        var width = insets.left + insets.right + hgap
        for (i in 0 until target.componentCount) {
            val m = target.getComponent(i)
            if (m.isVisible) width += m.preferredSize.width + hgap
        }
        return width
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        // 面板尚未布局（width=0）时向上找最近的有效宽度；仍无则按不限宽的单行计算
        var container: Container = target
        var targetWidth = container.width
        while (targetWidth <= 0 && container.parent != null) {
            container = container.parent
            targetWidth = container.width
        }
        val insets: Insets = target.insets
        val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
        val maxWidth = if (targetWidth <= 0) Int.MAX_VALUE else targetWidth - horizontalInsetsAndGap

        val dim = Dimension(0, 0)
        var rowWidth = 0
        var rowHeight = 0
        for (i in 0 until target.componentCount) {
            val m = target.getComponent(i)
            if (m.isVisible) {
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                rowWidth += d.width + hgap
                rowHeight = maxOf(rowHeight, d.height)
            }
        }
        addRow(dim, rowWidth, rowHeight)
        dim.width += horizontalInsetsAndGap
        dim.height += insets.top + insets.bottom + vgap * 2
        return dim
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }

    override fun layoutContainer(target: Container) {
        val size = preferredLayoutSize(target)
        if (size == lastPreferredSize) {
            super.layoutContainer(target)
        } else {
            // 换行数变化导致首选尺寸变化：冒泡一次整树校验，
            // 让上层 BoxLayout 按新的多行高度重新分配空间
            lastPreferredSize = size
            var top: Container = target
            while (top.parent != null) top = top.parent
            top.validate()
        }
    }
}
