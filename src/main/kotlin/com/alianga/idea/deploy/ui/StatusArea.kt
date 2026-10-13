package com.alianga.idea.deploy.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * 操作页底部固定位置的状态区（实施方案 3.6），三种状态互斥：
 * - 空闲：两行摘要（将要做什么 + 流程），缺项时红字说明（错误不只靠颜色，附文字）
 * - 运行中：文案 + 进度条 + 取消
 * - 完成 / 取消：结果行 + 日志 / 报告 / 关闭
 *
 * 进度条与文案组件由外部持有并更新（沿用原有 progressBar / progressLabel 的写法）。
 *
 * @author 郑明亮
 */
class StatusArea(
    val progressBar: JProgressBar,
    val progressLabel: JBLabel,
    cancelText: String,
    private val onCancel: () -> Unit,
    logText: String, private val onLog: () -> Unit,
    reportText: String, private val onReport: () -> Unit,
    closeText: String
) : JPanel(CardLayout()) {

    /** 可截断标签：首选宽度不随文本增长（超长时 JLabel 自动省略号），完整文本进 tooltip */
    class TruncLabel : JBLabel() {
        override fun setText(text: String?) { super.setText(text); toolTipText = text }
        override fun getPreferredSize(): Dimension = super.getPreferredSize().also { it.width = minOf(it.width, JBUI.scale(120)) }
        override fun getMinimumSize(): Dimension = Dimension(0, super.getMinimumSize().height)
    }

    val headline = TruncLabel()
    val flow = TruncLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont() }
    /** 1.1.1：运行中取消为 × 图标按钮（tooltip「取消」），不再挤短进度条 */
    val cancelButton = iconButton(AllIcons.Actions.Close, cancelText) { onCancel() }
    private val percentLabel = JBLabel().apply { font = JBUI.Fonts.smallFont(); foreground = UIUtil.getContextHelpForeground() }
    private val resultLabel = TruncLabel()
    val logLink = ActionLink(logText) { onLog() }
    val reportLink = ActionLink(reportText) { onReport() }
    val closeLink = iconButton(AllIcons.Actions.Close, closeText) { showIdle() }

    init {
        border = JBUI.Borders.empty(8, 0, 4, 0)
        val idle = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headline); add(flow)
        }
        progressBar.preferredSize = JBUI.size(100, 4)
        // 1.1.1：运行中——单行「文案 · 百分比 · ×」，下面单独一条 4px 进度条（原型）
        progressBar.addChangeListener { percentLabel.text = if (progressBar.isIndeterminate) "" else "${progressBar.value}%" }
        val runningRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(progressLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(4), 0)).apply { add(percentLabel); add(cancelButton) }, BorderLayout.EAST)
        }
        val running = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(runningRow, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
        }
        // 1.1.1：完成——单行「图标 结果（正文色，可截断） 日志 报告 ×」
        val done = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(resultLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(8), 0)).apply {
                add(logLink); add(reportLink); add(closeLink)
            }, BorderLayout.EAST)
        }
        add(idle, IDLE); add(running, RUNNING); add(done, DONE)
        showIdle()
    }

    fun showIdle() = (layout as CardLayout).show(this, IDLE)

    fun showRunning() {
        cancelButton.isEnabled = true
        (layout as CardLayout).show(this, RUNNING)
    }

    /** @param kind 0 成功 / 1 失败 / 2 取消 */
    fun showDone(text: String, kind: Int) {
        resultLabel.text = text
        // 文字保持正文色，只给图标上色；失败与取消用警告图标（原型）
        resultLabel.icon = if (kind == 0) AllIcons.General.InspectionsOK else AllIcons.General.Warning
        resultLabel.foreground = UIUtil.getLabelForeground()
        (layout as CardLayout).show(this, DONE)
    }

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, action: () -> Unit): JButton = JButton(icon).apply {
        toolTipText = tooltip
        accessibleContext.accessibleName = tooltip
        isBorderPainted = false; isContentAreaFilled = false; isFocusable = true
        preferredSize = JBUI.size(24, 24)
        addActionListener { action() }
    }

    companion object {
        private const val IDLE = "idle"
        private const val RUNNING = "running"
        private const val DONE = "done"
    }
}
