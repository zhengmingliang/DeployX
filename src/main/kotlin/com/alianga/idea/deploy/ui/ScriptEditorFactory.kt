package com.alianga.idea.deploy.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * 脚本编辑器工厂。
 *
 * 统一创建基于 IDEA 平台 [EditorTextField] 的脚本编辑组件，替代原先的
 * `JBTextArea` + 手写 `LineNumberGutter` 方案，获得：
 * - 语法高亮（Shell Script，若 IDE 提供则自动启用）
 * - 内置行号、代码折叠、当前行高亮
 * - 主题自适应（Darcula / Light）
 * - 滚动条、等宽编辑器字体
 *
 * FileType 探测逻辑：优先查找 "Shell Script"（IDEA Ultimate 默认捆绑的 Shell Script 插件提供），
 * 未找到时降级为 [PlainTextFileType]，保证不依赖外部插件也能工作。
 */
object ScriptEditorFactory {

    /** 脚本对应的 FileType：Shell Script 优先，降级 PlainText */
    val scriptFileType: FileType by lazy {
        runCatching { FileTypeManager.getInstance().findFileTypeByName("Shell Script") }
            .getOrNull()?.takeIf { it !== PlainTextFileType.INSTANCE }
            ?: PlainTextFileType.INSTANCE
    }

    /** 创建可编辑的脚本编辑器 */
    fun createEditable(
        text: String = "",
        project: Project? = null,
        showLineNumbers: Boolean = true
    ): EditorTextField = ScriptEditorField(
        EditorFactory.getInstance().createDocument(text),
        project, scriptFileType, isViewer = false, showLineNumbers
    )

    /** 创建只读的脚本预览编辑器 */
    fun createViewer(
        text: String = "",
        project: Project? = null
    ): EditorTextField = ScriptEditorField(
        EditorFactory.getInstance().createDocument(text),
        project, scriptFileType, isViewer = true, showLineNumbers = true
    )
}

/**
 * 带行号、滚动条与语法高亮的 [EditorTextField]。
 *
 * 父类 [EditorTextField.createEditor] 内部调用 [EditorTextField.setupTextFieldEditor]
 * 会默认关闭行号、折叠、滚动条；这里在覆盖中按需重新启用。
 *
 * 关键修复：在 [DialogWrapper][com.intellij.openapi.ui.DialogWrapper] 中使用时，
 * Enter 键会触发默认按钮（OK）、Tab 键会切换焦点。这里在 contentComponent 的
 * WHEN_FOCUSED 级别注册按键拦截，手动执行换行/缩进并消费事件，阻止冒泡。
 * 另外强制 [setOneLineMode][EditorEx.setOneLineMode]`(false)` 确保多行模式，
 * 避免粘贴多行文本时换行符被吞掉。
 *
 * 滚轮：内层编辑器默认会消费鼠标滚轮。挂在滚动表单（如 MappingEditDialog /
 * ScriptEditDialog）里时，只有编辑器内容超出视口且该方向还能继续滚时才自己消化，
 * 其余滚轮转发给外层 [JScrollPane]。
 */
private class ScriptEditorField(
    document: Document,
    project: Project?,
    private val scriptFileType: FileType,
    isViewer: Boolean,
    private val showLineNumbers: Boolean
) : EditorTextField(document, project, scriptFileType, isViewer) {

    override fun createEditor(): EditorEx {
        return super.createEditor().apply {
            val settings = settings
            if (showLineNumbers) {
                settings.setLineNumbersShown(true)
                settings.setLineMarkerAreaShown(true)
            }
            settings.setCaretRowShown(true)
            settings.setFoldingOutlineShown(showLineNumbers)
            setVerticalScrollbarVisible(true)
            setHorizontalScrollbarVisible(true)
            setOneLineMode(false) // 强制多行，防止粘贴多行时换行符丢失
            ensureSyntaxHighlighter()
            if (!isViewer) {
                preventKeystrokesFromTriggeringDialogActions()
            }
            forwardUnusedWheelToEnclosingScrollPane()
        }
    }

    /** project 为 null 时父类不会设置 highlighter，这里手动补上以保证语法高亮 */
    private fun EditorEx.ensureSyntaxHighlighter() {
        if (project != null) return
        val syntaxHighlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(scriptFileType, null, null) ?: return
        highlighter = LexerEditorHighlighter(syntaxHighlighter, colorsScheme)
    }

    /**
     * 将编辑器不需要的滚轮事件转发给外层 [JScrollPane]。
     * 仅当内容超出视口且滚轮方向上尚未顶到边界时，才由编辑器内部滚动。
     */
    private fun EditorEx.forwardUnusedWheelToEnclosingScrollPane() {
        val host: JComponent = this@ScriptEditorField
        val listener = MouseWheelListener { e ->
            if (e.isConsumed) return@MouseWheelListener
            if (canConsumeWheel(e)) return@MouseWheelListener
            val outer = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, host) as JScrollPane?
                ?: return@MouseWheelListener
            e.consume()
            val p: Point = SwingUtilities.convertPoint(e.component, e.point, outer)
            outer.dispatchEvent(
                MouseWheelEvent(
                    outer,
                    e.id,
                    e.`when`,
                    e.modifiersEx,
                    p.x,
                    p.y,
                    e.xOnScreen,
                    e.yOnScreen,
                    e.clickCount,
                    e.isPopupTrigger,
                    e.scrollType,
                    e.scrollAmount,
                    e.wheelRotation,
                    e.preciseWheelRotation
                )
            )
        }
        contentComponent.addMouseWheelListener(listener)
        gutterComponentEx.addMouseWheelListener(listener)
    }

    /** 内容高于/宽于视口，且滚轮方向上还能继续滚动时，由编辑器自己消化滚轮。 */
    private fun EditorEx.canConsumeWheel(e: MouseWheelEvent): Boolean {
        val visible = scrollingModel.visibleArea
        val content = contentSize
        val delta = if (e.preciseWheelRotation != 0.0) e.preciseWheelRotation else e.wheelRotation.toDouble()
        if (delta == 0.0) return false
        return if (e.isShiftDown) {
            when {
                content.width <= visible.width -> false
                delta < 0 -> visible.x > 0
                else -> visible.x + visible.width < content.width
            }
        } else {
            when {
                content.height <= visible.height -> false
                delta < 0 -> visible.y > 0
                else -> visible.y + visible.height < content.height
            }
        }
    }

    /**
     * 在 DialogWrapper 中，Enter 键会触发默认按钮（OK）、Tab 键会切换焦点。
     * 在 contentComponent 上注册 WHEN_FOCUSED 级别的 binding，消费这两个按键并
     * 手动执行换行/缩进，阻止事件冒泡到 DialogWrapper。
     */
    private fun EditorEx.preventKeystrokesFromTriggeringDialogActions() {
        val content = contentComponent

        // Enter：手动插入换行符（替代被 DialogWrapper 默认按钮拦截的编辑器 Enter action）
        content.registerKeyboardAction(
            { _ ->
                val doc = document
                val caret = caretModel
                val offset = caret.offset
                ApplicationManager.getApplication().runWriteAction {
                    doc.insertString(offset, "\n")
                    caret.moveToOffset(offset + 1)
                }
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED
        )

        // Tab：手动插入 Tab 字符（替代被 DialogWrapper 焦点切换拦截的编辑器 Tab action）
        content.registerKeyboardAction(
            { _ ->
                val doc = document
                val caret = caretModel
                val offset = caret.offset
                ApplicationManager.getApplication().runWriteAction {
                    doc.insertString(offset, "\t")
                    caret.moveToOffset(offset + 1)
                }
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0),
            JComponent.WHEN_FOCUSED
        )
    }
}
