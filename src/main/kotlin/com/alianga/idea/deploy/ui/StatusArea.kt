package com.alianga.idea.deploy.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
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

    val headline = JBLabel()
    val flow = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont() }
    val cancelButton = JButton(cancelText, AllIcons.Actions.Suspend).apply { addActionListener { onCancel() } }
    private val resultLabel = JBLabel()
    val logLink = ActionLink(logText) { onLog() }
    val reportLink = ActionLink(reportText) { onReport() }
    val closeLink = ActionLink(closeText) { showIdle() }

    init {
        border = JBUI.Borders.empty(8, 0, 4, 0)
        val idle = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(headline); add(flow)
        }
        progressBar.preferredSize = JBUI.size(100, 4)
        val running = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(2))).apply {
            add(progressLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
            add(cancelButton, BorderLayout.EAST)
        }
        val done = JPanel(BorderLayout()).apply {
            add(resultLabel, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(12), 0)).apply {
                add(logLink); add(reportLink); add(closeLink)
            }, BorderLayout.SOUTH)
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
        resultLabel.icon = when (kind) { 0 -> AllIcons.General.InspectionsOK; 1 -> AllIcons.General.Error; else -> AllIcons.General.Warning }
        resultLabel.foreground = when (kind) { 0 -> DeployXColors.success; 1 -> DeployXColors.error; else -> DeployXColors.warning }
        (layout as CardLayout).show(this, DONE)
    }

    companion object {
        private const val IDLE = "idle"
        private const val RUNNING = "running"
        private const val DONE = "done"
    }
}
