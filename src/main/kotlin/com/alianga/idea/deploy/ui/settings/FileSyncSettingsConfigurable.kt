package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTabbedPane
import javax.swing.JComponent

/**
 * 文件同步工具设置页面 - 注册在 Settings > Tools > DeployX
 */
class FileSyncSettingsConfigurable : Configurable {

    private var mainPanel: JBTabbedPane? = null
    private val serverPanel = ServerSettingsPanel()
    private val mappingPanel = MappingSettingsPanel()
    private val rsyncPanel = RsyncSettingsPanel()
    private val scriptPanel = ScriptSettingsPanel()

    override fun getDisplayName(): String = DeployXBundle.message("settings.title")

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            mainPanel = JBTabbedPane().apply {
                addTab(DeployXBundle.message("settings.tab.serverManagement"), serverPanel)
                addTab(DeployXBundle.message("settings.tab.mappingManagement"), mappingPanel)
                addTab(DeployXBundle.message("settings.tab.rsyncConfig"), rsyncPanel)
                addTab(DeployXBundle.message("settings.tab.scriptLibrary"), scriptPanel)
            }
        }
        return mainPanel!!
    }

    override fun isModified(): Boolean {
        return serverPanel.isModified() || mappingPanel.isModified() || rsyncPanel.isModified() || scriptPanel.isModified()
    }

    override fun apply() {
        serverPanel.apply()
        mappingPanel.apply()
        rsyncPanel.apply()
        scriptPanel.apply()
    }

    override fun reset() {
        serverPanel.reset()
        mappingPanel.reset()
        rsyncPanel.reset()
        scriptPanel.reset()
    }

    override fun disposeUIResources() {
        mainPanel = null
    }
}
