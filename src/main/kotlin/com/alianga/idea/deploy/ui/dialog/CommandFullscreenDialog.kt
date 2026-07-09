package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.ui.LineNumberGutter
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 命令全屏编辑对话框。
 *
 * 用于在映射编辑界面中放大查看/编辑命令内容，
 * 顶部右上角提供缩小恢复按钮，关闭时自动将内容同步回原输入组件。
 */
class CommandFullscreenDialog(
    private val project: Project?,
    private val originalText: String,
    private val sourceFont: Font,
    private val dialogTitle: String,
    private val onCommit: (String) -> Unit
) : DialogWrapper(project) {

    private val textArea: JBTextArea = JBTextArea().apply {
        font = sourceFont.deriveFont(sourceFont.size + 1f)
        tabSize = 2
        text = originalText
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
    }

    init {
        title = dialogTitle
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // ── 顶部工具栏：标题 + 缩小恢复按钮 ──
        val restoreButton = JButton(DeployXBundle.message("command.field.fullscreen.restore"), AllIcons.General.CollapseComponent).apply {
            toolTipText = DeployXBundle.message("command.field.fullscreen.restore.tooltip")
            addActionListener { commitAndClose() }
        }
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(restoreButton, BorderLayout.EAST)
            // 给工具栏底部留一点间距
            border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }

        panel.add(toolbar, BorderLayout.NORTH)

        // ── 中央：带行号的代码编辑区域 ──
        val scrollPane = JBScrollPane(textArea).apply {
            setRowHeaderView(LineNumberGutter(textArea))
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_ALWAYS
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        // 填满大部分屏幕
        val screen = Toolkit.getDefaultToolkit().screenSize
        panel.preferredSize = Dimension(
            (screen.width * 0.82).toInt(),
            (screen.height * 0.78).toInt()
        )

        return panel
    }

    /** 返回空数组，不展示默认 OK/Cancel 按钮 */
    override fun createActions(): Array<Action> = emptyArray()

    override fun doOKAction() {
        commitAndClose()
    }

    override fun doCancelAction() {
        // Escape 也同步内容后关闭
        commitAndClose()
    }

    private fun commitAndClose() {
        onCommit(textArea.text)
        close(OK_EXIT_CODE)
    }
}
