package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.util.ConfigExporter
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JComponent

/**
 * 文件同步工具设置页面 - 注册在 Settings > Tools > DeployX
 */
class FileSyncSettingsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private val serverPanel = ServerSettingsPanel()
    private val mappingPanel = MappingSettingsPanel()
    private val rsyncPanel = RsyncSettingsPanel()
    private val scriptPanel = ScriptSettingsPanel()
    private var tabbedPane: JBTabbedPane? = null

    override fun getDisplayName(): String = DeployXBundle.message("settings.title")

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            tabbedPane = JBTabbedPane().apply {
                addTab(DeployXBundle.message("settings.tab.serverManagement"), serverPanel)
                addTab(DeployXBundle.message("settings.tab.mappingManagement"), mappingPanel)
                addTab(DeployXBundle.message("settings.tab.rsyncConfig"), rsyncPanel)
                addTab(DeployXBundle.message("settings.tab.scriptLibrary"), scriptPanel)
            }
            val buttonPanel = JPanel().apply {
                add(JButton(DeployXBundle.message("settings.button.exportConfig"), AllIcons.Actions.Download).apply {
                    addActionListener { exportConfig() }
                })
                add(JButton(DeployXBundle.message("settings.button.importConfig"), AllIcons.Actions.Upload).apply {
                    addActionListener { importConfig() }
                })
            }
            mainPanel = JPanel(BorderLayout()).apply {
                add(buttonPanel, BorderLayout.NORTH)
                add(tabbedPane, BorderLayout.CENTER)
            }
        }
        return mainPanel!!
    }

    /**
     * 导出所有配置（加密）。
     */
    private fun exportConfig() {
        val password = Messages.showPasswordDialog(
            DeployXBundle.message("settings.config.export.passwordPrompt"),
            DeployXBundle.message("settings.button.exportConfig")
        )
        if (password.isNullOrBlank()) return

        val chooser = JFileChooser().apply {
            selectedFile = File("deployx-config-${System.currentTimeMillis()}.json")
        }
        if (chooser.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return

        val result = ConfigExporter.exportConfig(chooser.selectedFile, password)
        if (result.success) {
            Messages.showInfoMessage(
                DeployXBundle.message("settings.config.export.success", result.file?.absolutePath ?: ""),
                DeployXBundle.message("settings.button.exportConfig")
            )
        } else {
            Messages.showErrorDialog(
                DeployXBundle.message("settings.config.export.failed", result.error ?: ""),
                DeployXBundle.message("settings.button.exportConfig")
            )
        }
    }

    /**
     * 导入配置（解密）。
     */
    private fun importConfig() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return

        val password = Messages.showPasswordDialog(
            DeployXBundle.message("settings.config.import.passwordPrompt"),
            DeployXBundle.message("settings.button.importConfig")
        )
        if (password.isNullOrBlank()) return

        try {
            val hasConflicts = ConfigExporter.hasIdConflicts(chooser.selectedFile, password)
            val overwrite = if (hasConflicts) {
                val choice = Messages.showYesNoCancelDialog(
                    DeployXBundle.message("settings.config.import.conflictPrompt"),
                    DeployXBundle.message("settings.button.importConfig"),
                    Messages.getQuestionIcon()
                )
                if (choice == Messages.CANCEL) return
                choice == Messages.YES
            } else false

            val result = ConfigExporter.importConfig(chooser.selectedFile, password, overwrite, overwrite, overwrite)
            Messages.showInfoMessage(
                DeployXBundle.message("settings.config.import.success",
                    result.serversAdded, result.serversUpdated,
                    result.mappingsAdded, result.mappingsUpdated,
                    result.scriptsAdded, result.scriptsUpdated),
                DeployXBundle.message("settings.button.importConfig")
            )
            // 刷新面板
            serverPanel.reset()
            mappingPanel.reset()
            scriptPanel.refreshTable()
        } catch (e: Exception) {
            Messages.showErrorDialog(
                DeployXBundle.message("settings.config.import.failed", e.message ?: ""),
                DeployXBundle.message("settings.button.importConfig")
            )
        }
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
        tabbedPane = null
    }
}
