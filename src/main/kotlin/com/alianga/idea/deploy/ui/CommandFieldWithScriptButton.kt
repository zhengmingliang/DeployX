package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.ui.dialog.CommandFullscreenDialog
import com.alianga.idea.deploy.ui.dialog.ScriptPickerDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * 带脚本库选择按钮和全屏编辑按钮的命令输入组件，支持多行编辑。
 *
 * 统一提供三种脚本操作模式：
 * 1. 手动输入命令 — 直接在编辑器中编辑
 * 2. 从脚本库中引用已有脚本 — 点击右侧按钮，在脚本选择窗口中选择"引用脚本"模式
 * 3. 将脚本内容插入到当前编辑位置 — 点击右侧按钮，在脚本选择窗口中选择"插入脚本内容"模式
 *
 * 多行模式使用 IDEA 平台 [EditorTextField]（语法高亮、行号、主题自适应、代码折叠），
 * 单行模式使用 [JBTextField]。
 *
 * 当指定 fullscreenTitle 时，编辑器末端会额外显示全屏按钮，
 * 点击后在弹出对话框中全屏编辑命令内容。
 *
 * @param project 当前项目，用于脚本选择对话框和 EditorTextField（可为 null）
 * @param contextProvider 构建 ScriptRunContext 的回调，用于脚本模板渲染
 * @param multiline true 使用多行 EditorTextField；false 使用单行 JBTextField
 * @param preferredScrollSize 多行模式下编辑器的首选尺寸，null 则使用默认
 * @param showLineNumbers 多行模式下是否显示行号，默认 true
 * @param fullscreenTitle 全屏编辑对话框的标题。为 null 时不显示全屏按钮
 */
class CommandFieldWithScriptButton(
    private val project: Project?,
    private val contextProvider: () -> ScriptRunContext,
    multiline: Boolean = false,
    preferredScrollSize: Dimension? = null,
    showLineNumbers: Boolean = true,
    private val fullscreenTitle: String? = null
) : JPanel(BorderLayout(4, 0)) {

    /** 多行模式：基于 IDEA 编辑器的 EditorTextField；单行模式：JBTextField */
    private val editorField: EditorTextField? = if (multiline) {
        ScriptEditorFactory.createEditable("", project, showLineNumbers)
    } else null

    private val singleField: JBTextField? = if (!multiline) JBTextField() else null

    private val scriptButton: JButton = JButton(AllIcons.Actions.AddMulticaret).apply {
        toolTipText = DeployXBundle.message("command.field.script.picker.tooltip")
        isFocusable = false
        addActionListener { openScriptPicker() }
    }

    /** 全屏按钮，仅当 fullscreenTitle 非空时创建 */
    private val fullscreenButton: JButton? = if (fullscreenTitle != null) {
        JButton(AllIcons.General.ExpandComponent).apply {
            toolTipText = DeployXBundle.message("command.field.fullscreen.expand.tooltip")
            isFocusable = false
            addActionListener { openFullscreen() }
        }
    } else {
        null
    }

    init {
        if (editorField != null) {
            if (preferredScrollSize != null) editorField.preferredSize = preferredScrollSize
            add(editorField, BorderLayout.CENTER)
        } else if (singleField != null) {
            add(singleField, BorderLayout.CENTER)
        }

        // 右侧按钮面板：脚本库按钮在上，全屏按钮在下
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        buttonPanel.add(scriptButton)
        if (fullscreenButton != null) {
            buttonPanel.add(fullscreenButton)
        }
        add(buttonPanel, BorderLayout.EAST)
    }

    /** 获取/设置文本内容 */
    var text: String
        get() = editorField?.text ?: singleField?.text ?: ""
        set(value) {
            editorField?.text = value
            singleField?.let {
                it.text = value
                it.caretPosition = 0
            }
        }

    /** 是否可编辑 */
    var editable: Boolean
        get() = editorField?.isViewer == false || singleField?.isEditable == true
        set(value) {
            editorField?.setViewer(!value)
            singleField?.isEditable = value
        }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        editorField?.setEnabled(enabled)
        singleField?.let {
            it.isEnabled = enabled
            it.isEditable = enabled
        }
        scriptButton.isEnabled = enabled
        fullscreenButton?.isEnabled = enabled
    }

    /** 获取内部文本组件内容并 trim */
    fun trimmedText(): String = text.trim()

    /** 刷新脚本按钮的 tooltip（语言切换时调用） */
    fun updateTooltip() {
        scriptButton.toolTipText = DeployXBundle.message("command.field.script.picker.tooltip")
        fullscreenButton?.toolTipText = DeployXBundle.message("command.field.fullscreen.expand.tooltip")
    }

    // ─── 脚本选择 ────────────────────────────────────────────────

    private fun openScriptPicker() {
        val context = contextProvider()
        val dialog = ScriptPickerDialog(project, context)
        if (dialog.showAndGet()) {
            val command = dialog.getResultCommand()
            val field = editorField
            if (field != null) {
                // 多行模式：在光标位置插入
                field.requestFocus()
                insertIntoEditor(field, command)
            } else if (singleField != null) {
                // 单行模式：替换整个内容
                singleField.text = command
            }
        }
    }

    /** 在 EditorTextField 光标位置插入命令文本 */
    private fun insertIntoEditor(field: EditorTextField, command: String) {
        val doc: Document = field.document
        val editor = field.editor
        val pos = editor?.caretModel?.offset ?: doc.textLength
        val before = doc.text.substring(0, pos)
        val after = doc.text.substring(pos)
        val insertPrefix = if (before.isNotEmpty() && !before.endsWith('\n')) "\n" else ""
        val insertSuffix = if (after.isNotEmpty() && !after.startsWith('\n')) "\n" else ""
        val insertText = insertPrefix + command + insertSuffix
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(pos, insertText)
        }
        val newCaret = (pos + insertText.length).coerceIn(0, doc.textLength)
        editor?.caretModel?.moveToOffset(newCaret)
    }

    // ─── 全屏编辑 ────────────────────────────────────────────────

    private fun openFullscreen() {
        val title = fullscreenTitle ?: return
        val currentText = text
        val dialog = CommandFullscreenDialog(
            project = project,
            originalText = currentText,
            dialogTitle = title
        ) { newText ->
            text = newText
        }
        dialog.show()
    }
}
