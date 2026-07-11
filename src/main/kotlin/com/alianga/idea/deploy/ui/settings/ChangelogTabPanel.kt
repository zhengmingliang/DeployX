package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.text.html.HTMLEditorKit

/**
 * 设置页「版本更新说明」面板。
 *
 * 复用项目中唯一的更新内容来源 changelog.html（resources 根目录，与插件市场的
 * changeNotes 共用同一份），按当前语言只展示对应语言的更新内容，不在代码里维护
 * 第二份文案。
 *
 * 重要：Swing 的 HTMLEditorKit 只支持非常有限的 CSS 子集。若 HTML 中出现它不认识的
 * CSS 属性（如 border-radius、rgba()、line-height、带引号的 font-family 列表等），
 * 解析 <style> 时会抛出 NPE（CSS.getInternalCSSValue 返回 null 的 conv），从而连累
 * 整个设置页无法实例化。因此这里只用受支持的 color / margin / padding-left，颜色统一
 * 取自主题的 JBColor，保证深/浅色下都可读。
 *
 * 布局注意：JEditorPane("text/html") 会按内容推出一个超大首选宽度（尤其遇到长代码路径
 * 这类不可断行的 token），而 JTabbedPane 取所有 Tab 中最大首选尺寸作为整个对话框的尺寸。
 * 若不限制，这个 Tab 会把设置对话框横向撑得极宽，导致「脚本库」等其他 Tab 的表格被拉变形。
 * 因此这里对面板首选尺寸封顶，并让 JEditorPane 按实际可用宽度换行，溢出只在 Tab 内部滚动。
 */
class ChangelogTabPanel : JPanel(BorderLayout()) {

    // 限制 JEditorPane 的首选宽度，使其按实际可用宽度换行，而不是按内容推出超大宽度。
    // 高度保持自然值（由外层滚动面板负责纵向滚动）。
    private val editorPane = object : JEditorPane() {
        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            if (d.width > MAX_CONTENT_WIDTH) d.width = MAX_CONTENT_WIDTH
            return d
        }
    }

    init {
        editorPane.contentType = "text/html"
        editorPane.isEditable = false
        val scroll = JBScrollPane(
            editorPane,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        )
        add(scroll, BorderLayout.CENTER)
        refresh()
    }

    /** 根据当前语言重新渲染更新说明（语言切换时由外部监听调用）。 */
    fun refresh() {
        val lang = if (DeployXBundle.currentLanguageTag() == "zh_CN") "ZH" else "EN"
        val raw = loadChangelogHtml()
        val block = extractBlock(raw, lang)

        val fg = JBColor.foreground()
        val bg = UIUtil.getPanelBackground()

        val kit = HTMLEditorKit()
        // 只使用 Swing 受支持的 CSS 属性，避免未知属性触发 CSS 解析 NPE。
        kit.styleSheet.addRule(
            """
            body { color: ${hex(fg)}; margin: 8px 12px; }
            h3   { color: ${hex(fg)}; margin: 16px 0 6px; }
            ul   { margin: 4px 0; padding-left: 18px; }
            li   { margin: 3px 0; }
            code { color: ${hex(fg)}; }
            """.trimIndent()
        )
        editorPane.editorKit = kit
        editorPane.background = bg
        editorPane.foreground = fg

        editorPane.text = "<html><body>$block</body></html>"
        editorPane.caretPosition = 0
    }

    /**
     * 限制整个 Tab 面板的首选尺寸，避免把设置对话框（含其他 Tab）的横向/纵向空间撑变形。
     * 内容过长时只在 Tab 内部出现滚动条。
     */
    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        if (d.width > MAX_PANEL_WIDTH) d.width = MAX_PANEL_WIDTH
        if (d.height > MAX_PANEL_HEIGHT) d.height = MAX_PANEL_HEIGHT
        return d
    }

    private fun hex(c: Color): String = "#%06x".format(c.rgb and 0xFFFFFF)

    private fun loadChangelogHtml(): String {
        return ChangelogTabPanel::class.java.getResourceAsStream("/changelog.html")?.use { stream ->
            stream.bufferedReader(StandardCharsets.UTF_8).readText()
        } ?: "<p>Changelog not found.</p>"
    }

    private fun extractBlock(html: String, lang: String): String {
        val startMarker = "<!--LANG:$lang-->"
        val startIndex = html.indexOf(startMarker)
        if (startIndex < 0) return ""
        val contentStart = startIndex + startMarker.length
        val endIndex = if (lang == "ZH") {
            val end = html.indexOf("<!--LANG:END-->")
            if (end < 0) html.length else end
        } else {
            val next = html.indexOf("<!--LANG:ZH-->")
            if (next < 0) html.length else next
        }
        return html.substring(contentStart, endIndex).trim()
    }

    companion object {
        // JEditorPane 换行所依据的首选宽度上限（px），也间接约束了 Tab 对对话框宽度的贡献。
        private const val MAX_CONTENT_WIDTH = 720
        // 整个 Tab 面板首选尺寸上限（px），防止对话框被撑得过大、连累其他 Tab 变形。
        private const val MAX_PANEL_WIDTH = 800
        private const val MAX_PANEL_HEIGHT = 600
    }
}
