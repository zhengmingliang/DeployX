package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.ui.dialog.CommandFullscreenDialog
import com.alianga.idea.deploy.ui.dialog.ScriptPickerDialog
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.text.JTextComponent

/**
 * 带脚本库选择按钮和全屏编辑按钮的命令输入组件，支持行号显示和多行编辑。
 *
 * 统一提供三种脚本操作模式：
 * 1. 手动输入命令 — 直接在文本框中编辑
 * 2. 从脚本库中引用已有脚本 — 点击右侧按钮，在脚本选择窗口中选择"引用脚本"模式
 * 3. 将脚本内容插入到当前编辑位置 — 点击右侧按钮，在脚本选择窗口中选择"插入脚本内容"模式
 *
 * 当指定 fullscreenTitle 时，文本框末端会额外显示全屏按钮，
 * 点击后在弹出对话框中全屏编辑命令内容。
 *
 * @param project 当前项目，用于脚本选择对话框（可为 null）
 * @param contextProvider 构建 ScriptRunContext 的回调，用于脚本模板渲染
 * @param multiline true 使用多行文本区域，附带行号边栏和滚动；false 使用单行文本框
 * @param preferredScrollSize 多行模式下滚动面板的首选尺寸，null 则使用默认
 * @param showLineNumbers 多行模式下是否显示行号边栏，默认 true
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

    /** 内部文本组件（JBTextField 或 JBTextArea） */
    val textComponent: JTextComponent = if (multiline) {
        JBTextArea(4, 50).apply {
            font = Font("Monospaced", Font.PLAIN, 13)
            lineWrap = true
            wrapStyleWord = true
            tabSize = 2
        }
    } else {
        JBTextField()
    }

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
        if (multiline && textComponent is JBTextArea) {
            val scrollPane = JBScrollPane(textComponent).apply {
                if (preferredScrollSize != null) preferredSize = preferredScrollSize
                if (showLineNumbers) {
                    setRowHeaderView(LineNumberGutter(textComponent))
                }
            }
            add(scrollPane, BorderLayout.CENTER)
        } else {
            add(textComponent, BorderLayout.CENTER)
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
        get() = textComponent.text
        set(value) {
            textComponent.text = value
            if (textComponent is JBTextArea) {
                textComponent.caretPosition = 0
            }
        }

    /** 是否可编辑 */
    var editable: Boolean
        get() = textComponent.isEditable
        set(value) {
            textComponent.isEditable = value
        }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        textComponent.isEnabled = enabled
        textComponent.isEditable = enabled
        scriptButton.isEnabled = enabled
        fullscreenButton?.isEnabled = enabled
    }

    /** 获取内部文本组件内容并 trim */
    fun trimmedText(): String = textComponent.text.trim()

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
            textComponent.requestFocus()
            if (textComponent is JBTextArea) {
                // 多行模式：在光标位置插入
                val pos = textComponent.caretPosition
                val before = textComponent.text.substring(0, pos)
                val after = textComponent.text.substring(pos)
                val insertPrefix = if (before.isNotEmpty() && !before.endsWith('\n')) "\n" else ""
                val insertSuffix = if (after.isNotEmpty() && !after.startsWith('\n')) "\n" else ""
                textComponent.text = before + insertPrefix + command + insertSuffix + after
                val newCaret = pos + insertPrefix.length + command.length + insertSuffix.length
                textComponent.caretPosition = newCaret.coerceIn(0, textComponent.text.length)
            } else {
                // 单行模式：替换整个内容
                textComponent.text = command
            }
        }
    }

    // ─── 全屏编辑 ────────────────────────────────────────────────

    private fun openFullscreen() {
        val title = fullscreenTitle ?: return
        val currentText = textComponent.text
        val currentFont = textComponent.font
        val dialog = CommandFullscreenDialog(
            project = project,
            originalText = currentText,
            sourceFont = currentFont,
            dialogTitle = title
        ) { newText ->
            textComponent.text = newText
            if (textComponent is JBTextArea) {
                textComponent.caretPosition = 0
            }
        }
        dialog.show()
    }
}
