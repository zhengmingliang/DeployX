package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.ui.ScriptEditorFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import java.awt.BorderLayout
import java.awt.Dimension
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
 *
 * 使用 IDEA 平台 [EditorTextField] 提供语法高亮、行号、主题自适应。
 */
class CommandFullscreenDialog(
    private val project: Project?,
    private val originalText: String,
    private val dialogTitle: String,
    private val onCommit: (String) -> Unit
) : DialogWrapper(project) {

    private val editorField: EditorTextField = ScriptEditorFactory.createEditable(originalText, project)

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

        // ── 中央：基于 IDEA 编辑器的代码编辑区域（自带行号、高亮、滚动） ──
        panel.add(editorField, BorderLayout.CENTER)

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
        onCommit(editorField.text)
        close(OK_EXIT_CODE)
    }
}
