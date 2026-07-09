package com.alianga.idea.deploy.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.text.JTextComponent

/**
 * 为文本区域添加行号边栏。
 *
 * 用法：
 * ```
 * val textArea = JBTextArea()
 * val scrollPane = JBScrollPane(textArea)
 * scrollPane.setRowHeaderView(LineNumberGutter(textArea))
 * ```
 *
 * 自动跟随文本变化和 wrap 更新行号范围和宽度。
 */
class LineNumberGutter(
    private val textComponent: JTextComponent,
    private val bgColor: Color = LBG,
    private val fgColor: Color = FG,
    private val borderColor: Color = BORDER
) : JComponent() {

    companion object {
        /** Light theme: #F2F2F2, Dark theme: IntelliJ default editor gutter */
        private val LBG = JBColor(Color(0xF2F2F2), Color(0x313335))
        /** Light theme: #999999, Dark theme: subdued grey */
        private val FG = JBColor(Color(0x999999), Color(0x6A6A6A))
        /** Light theme: #E0E0E0, Dark theme: dark separator */
        private val BORDER = JBColor(Color(0xE0E0E0), Color(0x3E3E3E))

        /** 水平内边距 */
        private const val PAD_LEFT = 6
        private const val PAD_RIGHT = 10
        /** 最小宽度 */
        private const val MIN_W = 32
    }

    private var cachedWidth = MIN_W

    init {
        font = textComponent.font.deriveFont(textComponent.font.size2D - 1f)
        textComponent.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = recalc()
            override fun removeUpdate(e: DocumentEvent) = recalc()
            override fun changedUpdate(e: DocumentEvent) = recalc()
        })
        recalc()
    }

    override fun getPreferredSize(): Dimension {
        // 宽度动态计算, 高度与文本区域一致
        val h = if (textComponent.parent is javax.swing.JViewport) {
            textComponent.preferredSize.height
        } else {
            textComponent.preferredSize.height.coerceAtLeast(1)
        }
        return Dimension(cachedWidth, h)
    }

    override fun setFont(font: Font) {
        super.setFont(font)
        recalc()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val w = width
        val h = height

        // 背景
        g.color = bgColor
        g.fillRect(0, 0, w, h)

        // 右侧分隔线
        g.color = borderColor
        g.drawLine(w - 1, 0, w - 1, h)

        // 画行号
        g.color = fgColor
        g.font = font
        val fm = g.fontMetrics
        val lineH = fm.height
        val ascent = fm.ascent

        val lineCount = getLogicalLineCount()
        val digitW = lineCount.toString().length

        try {
            for (i in 0 until lineCount) {
                val numStr = (i + 1).toString()
                val strW = fm.stringWidth(numStr)
                val x = PAD_LEFT + (cachedWidth - PAD_LEFT - PAD_RIGHT - strW - 1) // 右对齐
                val rect = viewRectForLine(i) ?: continue
                val y = rect.y.toInt() + ((rect.height.toInt() - lineH) / 2) + ascent
                g.drawString(numStr, x, y)
            }
        } catch (_: BadLocationException) {
        }
    }

    /** 获取某一行在文本区域中的可视矩形 */
    private fun viewRectForLine(lineIndex: Int): Rectangle2D? {
        return try {
            val root = textComponent.document.defaultRootElement
            if (lineIndex >= root.elementCount) return null
            val elem = root.getElement(lineIndex)
            textComponent.modelToView2D(elem.startOffset)?.bounds
        } catch (_: BadLocationException) {
            null
        }
    }

    /** 逻辑行数（不含自动换行） */
    private fun getLogicalLineCount(): Int {
        return textComponent.document.defaultRootElement.elementCount
    }

    private fun recalc() {
        val lines = getLogicalLineCount()
        val digits = if (lines < 10) 1 else lines.toString().length
        val fm = getFontMetrics(font)
        val textW = fm.stringWidth("8".repeat(digits))
        cachedWidth = (textW + PAD_LEFT + PAD_RIGHT + 1).coerceAtLeast(MIN_W)
        revalidate()
        repaint()
    }
}
