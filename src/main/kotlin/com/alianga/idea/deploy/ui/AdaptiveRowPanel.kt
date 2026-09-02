package com.alianga.idea.deploy.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 自适应行面板：宽度足够时 [side] 停靠 [wideSideConstraint]（默认 EAST）与 [main] 同行；
 * 宽度不足（放不下 [main] 的最小可用宽度 + [side]）时把 [side] 移到
 * [narrowSideConstraint]（默认 SOUTH），避免工具窗口拉窄时侧栏控件被裁剪。
 *
 * 默认用于操作面板「目标服务器」行（下拉框 + 浏览/终端/保存按钮组）：
 * 宽屏 EAST、窄屏 SOUTH。SOUTH 时侧栏按 leading 对齐，避免 BorderLayout 把
 * 按钮条拉满整行。
 *
 * 标签+字段行用 [labeled]：宽屏 WEST、窄屏 NORTH（标签在字段上方），
 * 规避 FormBuilder 两列 GridBag 下标签列不换行、字段被挤没的问题。
 */
class AdaptiveRowPanel(
    main: JComponent,
    private val side: JComponent,
    /** main 保持可用所需的最小宽度（已按 DPI 缩放） */
    private val minMainWidth: Int,
    private val hgap: Int = JBUI.scale(6),
    vgap: Int = JBUI.scale(4),
    private val wideSideConstraint: String = BorderLayout.EAST,
    private val narrowSideConstraint: String = BorderLayout.SOUTH,
    /** 窄屏叠放时侧栏不拉满整行，按钮/短控件保持 leading */
    private val keepSideLeadingWhenStacked: Boolean = true
) : JPanel(BorderLayout(hgap, vgap)) {

    private var sideOnWide = true
    private val stackedHolder = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
        isOpaque = false
    }

    init {
        add(main, BorderLayout.CENTER)
        add(side, wideSideConstraint)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = placeSide()
        })
    }

    override fun addNotify() {
        super.addNotify()
        placeSide()
    }

    private fun placeSide() {
        if (width <= 0) return
        val fitsOneLine = width >= minMainWidth + hgap + side.preferredSize.width
        if (fitsOneLine == sideOnWide) return
        sideOnWide = fitsOneLine
        remove(side)
        remove(stackedHolder)
        stackedHolder.remove(side)
        if (fitsOneLine) {
            add(side, wideSideConstraint)
        } else if (keepSideLeadingWhenStacked) {
            stackedHolder.add(side)
            add(stackedHolder, narrowSideConstraint)
        } else {
            add(side, narrowSideConstraint)
        }
        revalidate()
        repaint()
    }

    companion object {
        /**
         * 标签在左、字段在右；极窄时标签换到字段上方并拉满宽度，
         * 字段仍占一整行，避免 FormBuilder 标签列吃掉可用宽度。
         */
        fun labeled(
            label: JComponent,
            field: JComponent,
            minFieldWidth: Int = JBUI.scale(140)
        ): AdaptiveRowPanel = AdaptiveRowPanel(
            main = field,
            side = label,
            minMainWidth = minFieldWidth,
            wideSideConstraint = BorderLayout.WEST,
            narrowSideConstraint = BorderLayout.NORTH,
            keepSideLeadingWhenStacked = false
        )
    }
}
