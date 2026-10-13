package com.alianga.idea.deploy.ui

import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * 放进滚动面板时宽度始终等于视口宽度（只纵向滚动）：
 * 长说明 / 宽控件按可见宽度换行或收缩，不再撑出横向滚动条。
 *
 * @author 郑明亮
 */
open class ViewportWidthPanel(layout: LayoutManager) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) =
        if (o == SwingConstants.VERTICAL) r.height else r.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
