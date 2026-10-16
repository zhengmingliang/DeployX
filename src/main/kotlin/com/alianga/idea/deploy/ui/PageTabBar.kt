package com.alianga.idea.deploy.ui

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 自建页签行 + 内容卡片（对应原型「页签行」，评审 review-开发助手3 方案 A）。
 *
 * 平台内容页签在新 UI 下与标题栏抢宽度，窄于约 330px 会把「操作」折进下拉；这里自己画：
 * - 左侧页签按首选宽度**优先保留**，永不隐藏；
 * - 右侧放当前页签的 [ActionToolbar]（AUTO_LAYOUT，空间不足时动作折进 ⋯ 溢出菜单）；
 * - 下方 CardLayout 切换页面。
 *
 * @author 郑明亮
 */
class PageTabBar(private val onSelected: (Int) -> Unit) : JPanel(BorderLayout()) {

    private class Tab(text: String) : JBLabel(text) {
        var selected = false
            set(v) {
                field = v
                foreground = if (v) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                repaint()
            }
        var hovered = false
            set(v) {
                if (field != v) {
                    field = v
                    repaint()
                }
            }

        init {
            border = JBUI.Borders.empty(0, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            iconTextGap = JBUI.scale(4)
            horizontalTextPosition = LEFT
            isFocusable = true
            accessibleContext?.accessibleName = text
        }

        override fun setText(text: String?) {
            super.setText(text)
            accessibleContext?.accessibleName = text
        }

        override fun getPreferredSize(): Dimension = super.getPreferredSize().also { it.height = JBUI.scale(34) }
        override fun getMinimumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            if (hovered && !selected) {
                g.color = JBColor.namedColor("ActionButton.hoverBackground", JBColor(0xEDEFF2, 0x35373B))
                g.fillRect(0, 0, width, height)
            }
            super.paintComponent(g)
            if (selected) { // 选中态：底部 2px accent 下划线（原型 .tab.on）
                g.color = DeployXColors.accent
                g.fillRect(0, height - JBUI.scale(2), width, JBUI.scale(2))
            } else if (hasFocus()) {
                g.color = DeployXColors.accent
                g.drawRect(JBUI.scale(1), JBUI.scale(1), width - JBUI.scale(3), height - JBUI.scale(3))
            }
        }
    }

    private val tabs = mutableListOf<Tab>()
    private val tabsPanel = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply { isOpaque = false }
    private val toolbarCards = JPanel(CardLayout()).apply { isOpaque = false }
    private val contentCards = JPanel(CardLayout())
    var selectedIndex = -1
        private set

    init {
        val row = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(DeployXColors.border)
            // WEST 先拿首选宽度（页签永不被挤掉），CENTER 剩余宽度给工具栏
            add(tabsPanel, BorderLayout.WEST)
            add(toolbarCards, BorderLayout.CENTER)
        }
        add(row, BorderLayout.NORTH)
        add(contentCards, BorderLayout.CENTER)
    }

    fun addPage(title: String, content: JComponent, toolbar: ActionToolbar?) {
        val index = tabs.size
        val tab = Tab(title)
        tab.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = select(index)
            override fun mouseEntered(e: MouseEvent) { tab.hovered = true }
            override fun mouseExited(e: MouseEvent) { tab.hovered = false }
        })
        tab.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = tab.repaint()
            override fun focusLost(e: FocusEvent) = tab.repaint()
        })
        tab.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                        select(index)
                        e.consume()
                    }
                    KeyEvent.VK_LEFT -> {
                        val prev = (index - 1 + tabs.size) % tabs.size
                        select(prev)
                        tabs.getOrNull(prev)?.requestFocusInWindow()
                        e.consume()
                    }
                    KeyEvent.VK_RIGHT -> {
                        val next = (index + 1) % tabs.size
                        select(next)
                        tabs.getOrNull(next)?.requestFocusInWindow()
                        e.consume()
                    }
                }
            }
        })
        tabs += tab
        tabsPanel.add(tab)
        val tbHolder = JPanel(FlowLayout(FlowLayout.TRAILING, 0, JBUI.scale(2))).apply { isOpaque = false }
        val tbHolder2 = JPanel(BorderLayout()).apply { isOpaque = false }
        if (toolbar != null) {
            @Suppress("DEPRECATION") toolbar.setLayoutPolicy(ActionToolbar.AUTO_LAYOUT_POLICY)
            toolbar.component.isOpaque = false
            toolbar.component.border = JBUI.Borders.empty()
            tbHolder2.add(toolbar.component, BorderLayout.EAST)
        }
        toolbarCards.add(if (toolbar != null) tbHolder2 else tbHolder, index.toString())
        contentCards.add(content, index.toString())
        if (selectedIndex < 0) select(0)
    }

    fun select(index: Int) {
        if (index !in tabs.indices) return
        selectedIndex = index
        tabs.forEachIndexed { i, t -> t.selected = i == index }
        (toolbarCards.layout as CardLayout).show(toolbarCards, index.toString())
        (contentCards.layout as CardLayout).show(contentCards, index.toString())
        onSelected(index)
    }

    fun setTitle(index: Int, title: String) { tabs.getOrNull(index)?.text = title }

    /** 页签附加图标（例如运行中「日志」旁的 accent 小圆点），null 清除 */
    fun setBadge(index: Int, icon: Icon?) { tabs.getOrNull(index)?.icon = icon }
}
