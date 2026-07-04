package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * 语言选项枚举。
 *
 * @param value 持久化存储值，对应 [FileSyncSettings.language]
 * @param displayText 在下拉框中显示的文本（固定写死，不随当前语言变化，
 *                    保证用户始终能识别各选项代表的语言）
 */
enum class LanguageOption(val value: String, val displayText: String) {
    SYSTEM("system", "System Default"),
    ENGLISH("en", "English"),
    CHINESE("zh_CN", "简体中文");

    override fun toString(): String = displayText

    companion object {
        fun fromValue(value: String): LanguageOption =
            entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/**
 * 语言设置面板。提供一个语言下拉选择框。
 *
 * Apply 后仅写入持久化设置，无需重启 IDE —— 新打开的对话框/面板会自动使用新语言。
 * 已打开的工具窗口面板需重新打开才会刷新文本。
 */
class LanguageSettingsPanel : JPanel() {

    private val settings = FileSyncSettings.getInstance()

    private val languageCombo = JComboBox(LanguageOption.entries.toTypedArray())

    init {
        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        languageCombo.selectedItem = LanguageOption.fromValue(settings.language)
        add(languageCombo)
    }

    private fun loadSettings() {
        languageCombo.selectedItem = LanguageOption.fromValue(settings.language)
    }

    fun isModified(): Boolean {
        val selected = languageCombo.selectedItem as? LanguageOption ?: LanguageOption.SYSTEM
        return selected.value != settings.language
    }

    fun apply() {
        val selected = languageCombo.selectedItem as? LanguageOption ?: LanguageOption.SYSTEM
        val changed = selected.value != settings.language
        settings.language = selected.value
        // 语言变更后通知所有已注册面板重建文案（清空 bundle 缓存 + 触发监听器），
        // 使切换语言对新打开的对话框立即生效，并刷新已打开的工具窗口面板。
        if (changed) {
            DeployXBundle.notifyLanguageChanged()
        }
    }

    fun reset() {
        loadSettings()
    }
}
