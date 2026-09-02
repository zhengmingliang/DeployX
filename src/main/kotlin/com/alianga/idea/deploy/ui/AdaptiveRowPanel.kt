package com.alianga.idea.deploy.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 自适应行面板：宽度足够时 [side] 停靠右侧（EAST）与 [main] 同行；
 * 宽度不足（放不下 [main] 的最小可用宽度 + [side]）时自动把 [side]
 * 移到 [main] 下方（SOUTH，左对齐），避免工具窗口拉窄时右侧按钮
 * 被裁剪不可见。
 *
 * 用于操作面板"目标服务器"行（下拉框 + 浏览/终端/保存按钮组）等场景。
 */
class AdaptiveRowPanel(
    main: JComponent,
    private val side: JComponent,
    /** main 保持可用所需的最小宽度（已按 DPI 缩放） */
    private val minMainWidth: Int,
    private val hgap: Int = JBUI.scale(6),
    vgap: Int = JBUI.scale(4)
) : JPanel(BorderLayout(hgap, vgap)) {

    private var sideAtEast = true

    init {
        add(main, BorderLayout.CENTER)
        add(side, BorderLayout.EAST)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = placeSide()
        })
    }

    private fun placeSide() {
        val fitsOneLine = width >= minMainWidth + hgap + side.preferredSize.width
        if (fitsOneLine == sideAtEast) return
        sideAtEast = fitsOneLine
        remove(side)
        add(side, if (fitsOneLine) BorderLayout.EAST else BorderLayout.SOUTH)
        revalidate()
        repaint()
    }
}
