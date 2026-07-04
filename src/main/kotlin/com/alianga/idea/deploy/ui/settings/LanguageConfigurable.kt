package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

/**
 * 语言设置页 - 注册在 Settings > Tools > DeployX - Language。
 *
 * 此处 [getDisplayName] 通过 [DeployXBundle] 取值，会跟随当前语言实时变化
 * （设置页每次打开都重新构建 Configurable 实例）。
 */
class LanguageConfigurable : Configurable {

    private var panel: LanguageSettingsPanel? = null

    override fun getDisplayName(): String = DeployXBundle.message("settings.language.configurableName")

    override fun createComponent(): JComponent {
        if (panel == null) {
            panel = LanguageSettingsPanel()
        }
        return panel!!
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
