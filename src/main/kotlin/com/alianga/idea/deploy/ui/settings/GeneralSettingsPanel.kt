package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 通用设置面板 - 集中放置跨模块的通用偏好设置。
 *
 * 当前包含：
 * - 语言切换（复用 [LanguageSettingsPanel]，已封装语言变更通知逻辑）
 * - 部署/上传完成后的系统通知开关
 * - 日志区字体大小
 *
 * 后续新增的通用设置项（如主题、日志级别、默认行为等）可继续在此面板追加。
 */
class GeneralSettingsPanel : com.alianga.idea.deploy.ui.ViewportWidthPanel(BorderLayout()) {

    private val settings = FileSyncSettings.getInstance()

    /** 语言设置子面板（含下拉框 + apply 时的语言变更通知） */
    private val languagePanel = LanguageSettingsPanel()

    private val systemNotificationCheck = JBCheckBox(
        DeployXBundle.message("settings.general.systemNotification"),
        settings.systemNotification
    )

    /** 日志字体大小：8-36pt，步进 1 */
    private val logFontSizeSpinner = JSpinner(
        SpinnerNumberModel(settings.logFontSize, 8, 36, 1)
    )

    init {
        setupUI()
    }

    /** 切换语言后重建表单，使说明、复选框与标签文案跟随新语言 */
    fun relocalize() {
        removeAll()
        setupUI()
        revalidate()
        repaint()
    }

    private fun setupUI() {
        val infoLabel = wrapText(DeployXBundle.message("settings.general.info"), italic = true)

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(infoLabel)
            .addVerticalGap(8)
            .addLabeledComponent(DeployXBundle.message("settings.language.label"), languagePanel)
            .addComponent(wrapText(DeployXBundle.message("settings.language.description")))
            .addVerticalGap(8)
            .addComponent(systemNotificationCheck)
            .addComponent(wrapText(DeployXBundle.message("settings.general.systemNotification.desc")))
            .addVerticalGap(8)
            .addLabeledComponent(DeployXBundle.message("settings.general.logFontSize"), logFontSizeSpinner)
            .addComponent(wrapText(DeployXBundle.message("settings.general.logFontSize.desc")))
            .panel

        add(formPanel, BorderLayout.NORTH)
    }

    /**
     * 1.1.1：说明文字按面板宽度自动换行（HTML 标签不会换行，长说明被截断并出现横向滚动条）。
     * columns 取小值让首选宽度很窄，由 FormBuilder 横向拉伸后再按实际宽度折行。
     */
    private fun wrapText(text: String, italic: Boolean = false) = object : com.intellij.ui.components.JBTextArea(text) {
        // 换行文本区的首选宽度默认是整段不换行的宽度，会撑宽父容器；只报一个很小的宽度，由布局拉伸后再换行
        override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().also { it.width = com.intellij.util.ui.JBUI.scale(100) }
        override fun getMinimumSize(): java.awt.Dimension = java.awt.Dimension(0, super.getMinimumSize().height)
    }.apply {
        isEditable = false; isFocusable = false; isOpaque = false
        lineWrap = true; wrapStyleWord = true; columns = 10
        border = com.intellij.util.ui.JBUI.Borders.empty()
        val base = com.intellij.util.ui.UIUtil.getLabelFont()
        font = if (italic) base.deriveFont(java.awt.Font.ITALIC) else base.deriveFont(base.size2D - 1f)
        foreground = if (italic) com.intellij.util.ui.UIUtil.getLabelForeground() else com.intellij.util.ui.UIUtil.getContextHelpForeground()
    }

    fun isModified(): Boolean {
        return languagePanel.isModified() ||
                systemNotificationCheck.isSelected != settings.systemNotification ||
                (logFontSizeSpinner.value as Int) != settings.logFontSize
    }

    /** 仅判断日志字体大小是否被修改（用于 apply 后决定是否刷新已打开的日志区）。 */
    fun isLogFontSizeModified(): Boolean = (logFontSizeSpinner.value as Int) != settings.logFontSize

    fun apply() {
        languagePanel.apply()
        settings.systemNotification = systemNotificationCheck.isSelected
        settings.logFontSize = logFontSizeSpinner.value as Int
    }

    fun reset() {
        languagePanel.reset()
        systemNotificationCheck.isSelected = settings.systemNotification
        logFontSizeSpinner.value = settings.logFontSize
    }
}
