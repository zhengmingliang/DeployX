package com.alianga.idea.deploy.ui.settings

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

    override fun getDisplayName(): String = "DeployX"

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            mainPanel = JBTabbedPane().apply {
                addTab("服务器管理", serverPanel)
                addTab("目录映射", mappingPanel)
                addTab("rsync 配置", rsyncPanel)
            }
        }
        return mainPanel!!
    }

    override fun isModified(): Boolean {
        return serverPanel.isModified() || mappingPanel.isModified() || rsyncPanel.isModified()
    }

    override fun apply() {
        serverPanel.apply()
        mappingPanel.apply()
        rsyncPanel.apply()
    }

    override fun reset() {
        serverPanel.reset()
        mappingPanel.reset()
        rsyncPanel.reset()
    }

    override fun disposeUIResources() {
        mainPanel = null
    }
}
