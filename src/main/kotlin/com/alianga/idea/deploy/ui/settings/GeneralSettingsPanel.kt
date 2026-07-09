package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * 通用设置面板 - 集中放置跨模块的通用偏好设置。
 *
 * 当前包含：
 * - 语言切换（复用 [LanguageSettingsPanel]，已封装语言变更通知逻辑）
 * - 部署/上传完成后的系统通知开关
 *
 * 后续新增的通用设置项（如主题、日志级别、默认行为等）可继续在此面板追加。
 */
class GeneralSettingsPanel : JPanel(BorderLayout()) {

    private val settings = FileSyncSettings.getInstance()

    /** 语言设置子面板（含下拉框 + apply 时的语言变更通知） */
    private val languagePanel = LanguageSettingsPanel()

    private val systemNotificationCheck = JBCheckBox(
        DeployXBundle.message("settings.general.systemNotification"),
        settings.systemNotification
    )

    init {
        setupUI()
    }

    private fun setupUI() {
        val infoLabel = JBLabel("<html><i>${DeployXBundle.message("settings.general.info")}</i></html>")

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(infoLabel)
            .addVerticalGap(8)
            .addLabeledComponent(DeployXBundle.message("settings.language.label"), languagePanel)
            .addComponent(JBLabel("<html><small>${DeployXBundle.message("settings.language.description")}</small></html>"))
            .addVerticalGap(8)
            .addComponent(systemNotificationCheck)
            .addComponent(JBLabel("<html><small>${DeployXBundle.message("settings.general.systemNotification.desc")}</small></html>"))
            // TODO: 后续通用设置项在此追加
            .panel

        add(formPanel, BorderLayout.NORTH)
    }

    fun isModified(): Boolean {
        return languagePanel.isModified() ||
                systemNotificationCheck.isSelected != settings.systemNotification
    }

    fun apply() {
        languagePanel.apply()
        settings.systemNotification = systemNotificationCheck.isSelected
    }

    fun reset() {
        languagePanel.reset()
        systemNotificationCheck.isSelected = settings.systemNotification
    }
}
