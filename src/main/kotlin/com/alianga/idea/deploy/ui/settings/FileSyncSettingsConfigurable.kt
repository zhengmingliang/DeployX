package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.ServerManager
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
 * 文件同步工具设置页面 - 注册在 Settings > Tools → DeployX
 */
class FileSyncSettingsConfigurable : Configurable {

    private val settings = FileSyncSettings.getInstance()
    private var mainPanel: JPanel? = null
    private val generalPanel = GeneralSettingsPanel()
    private val serverPanel = ServerSettingsPanel()
    private val mappingPanel = MappingSettingsPanel()
    private val rsyncPanel = RsyncSettingsPanel()
    private val scriptPanel = ScriptSettingsPanel()
    private val changelogPanel = ChangelogTabPanel()
    private var tabbedPane: JBTabbedPane? = null
    private var langListenerRemover: (() -> Unit)? = null

    override fun getDisplayName(): String = DeployXBundle.message("settings.title")

    override fun createComponent(): JComponent {
        if (mainPanel == null) {
            tabbedPane = JBTabbedPane().apply {
                addTab(DeployXBundle.message("settings.tab.general"), generalPanel)
                addTab(DeployXBundle.message("settings.tab.serverManagement"), serverPanel)
                addTab(DeployXBundle.message("settings.tab.mappingManagement"), mappingPanel)
                addTab(DeployXBundle.message("settings.tab.rsyncConfig"), rsyncPanel)
                addTab(DeployXBundle.message("settings.tab.scriptLibrary"), scriptPanel)
                addTab(DeployXBundle.message("settings.tab.changelog"), changelogPanel)
                // 语言切换时刷新「版本更新说明」文案与 Tab 标题
                langListenerRemover = DeployXBundle.addLanguageChangeListener {
                    tabbedPane?.let {
                        it.setTitleAt(it.tabCount - 1, DeployXBundle.message("settings.tab.changelog"))
                    }
                    changelogPanel.refresh()
                }
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
     * 若存在使用密钥认证的服务器，先询问用户是否导出密钥文件内容。
     */
    private fun exportConfig() {
        val password = Messages.showPasswordDialog(
            DeployXBundle.message("settings.config.export.passwordPrompt"),
            DeployXBundle.message("settings.button.exportConfig")
        )
        if (password.isNullOrBlank()) {
            Messages.showWarningDialog(
                DeployXBundle.message("settings.config.export.passwordRequired"),
                DeployXBundle.message("settings.button.exportConfig")
            )
            return
        }

        // 检测是否有使用密钥认证的服务器
        val hasKeyServers = ServerManager.getInstance().getServers().any {
            it.authType == ServerConfig.AuthType.KEY && it.keyFile.isNotBlank()
        }
        val exportKeys = if (hasKeyServers) {
            val choice = Messages.showYesNoCancelDialog(
                DeployXBundle.message("settings.config.export.keyFilePrompt"),
                DeployXBundle.message("settings.config.export.keyFileTitle"),
                Messages.getQuestionIcon()
            )
            when (choice) {
                Messages.YES -> true
                Messages.NO -> false
                else -> return  // CANCEL
            }
        } else {
            false
        }

        val chooser = JFileChooser().apply {
            val lastDir = settings.lastExportDir
            if (lastDir.isNotEmpty()) {
                currentDirectory = File(lastDir)
            }
            selectedFile = File("deployx-config-${System.currentTimeMillis()}.json")
        }
        if (chooser.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return

        val result = ConfigExporter.exportConfig(chooser.selectedFile, password, exportKeys)
        if (result.success) {
            // 记住导出目录
            settings.lastExportDir = chooser.selectedFile.parent
            val message = if (result.keysExported > 0) {
                DeployXBundle.message(
                    "settings.config.export.withKeysSuccess",
                    result.keysExported,
                    result.file?.absolutePath ?: ""
                )
            } else {
                DeployXBundle.message("settings.config.export.success", result.file?.absolutePath ?: "")
            }
            // 若有缺失的密钥文件，附加提示
            val finalMessage = if (result.keysMissing.isNotEmpty()) {
                "$message\n\n${DeployXBundle.message("settings.config.export.keysMissing")}\n${result.keysMissing.joinToString("\n") { "• $it" }}"
            } else {
                message
            }
            Messages.showInfoMessage(finalMessage, DeployXBundle.message("settings.button.exportConfig"))
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
        val chooser = JFileChooser().apply {
            val lastDir = settings.lastImportDir
            if (lastDir.isNotEmpty()) {
                currentDirectory = File(lastDir)
            }
        }
        if (chooser.showOpenDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return

        // 密码重试循环：密码错误时重新输入，无需重新选择文件
        while (true) {
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

                // 构建成功消息
                val baseMessage = DeployXBundle.message(
                    "settings.config.import.success",
                    result.serversAdded, result.serversUpdated,
                    result.mappingsAdded, result.mappingsUpdated,
                    result.scriptsAdded, result.scriptsUpdated
                )
                val finalMessage = buildString {
                    append(baseMessage)
                    if (result.keysImported > 0) {
                        append("\n\n")
                        append(DeployXBundle.message(
                            "settings.config.import.keysSuccess",
                            result.keysImported
                        ))
                    }
                    if (result.keyMissingServers.isNotEmpty()) {
                        append("\n")
                        append(DeployXBundle.message("settings.config.import.keysMissing"))
                        result.keyMissingServers.forEach { missing ->
                            append("\n• $missing")
                        }
                    }
                }
                Messages.showInfoMessage(finalMessage, DeployXBundle.message("settings.button.importConfig"))
                // 记住导入目录
                settings.lastImportDir = chooser.selectedFile.parent
                // 刷新面板
                serverPanel.reset()
                mappingPanel.reset()
                scriptPanel.refreshTable()
                return  // 成功导入，退出循环
            } catch (e: Exception) {
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("Tag mismatch", ignoreCase = true)) {
                    // 密码错误：提示用户，继续循环重新输入密码
                    Messages.showErrorDialog(
                        DeployXBundle.message("settings.config.import.invalidPassword"),
                        DeployXBundle.message("settings.button.importConfig")
                    )
                    continue
                }
                // 其他错误（文件不存在、格式不对等），显示友好提示并退出
                val friendlyMessage = when {
                    e is java.io.FileNotFoundException ->
                        DeployXBundle.message("settings.config.import.invalidFile")
                    errorMsg.contains("Cannot invoke", ignoreCase = true) ||
                        e is com.google.gson.JsonSyntaxException ||
                        e is java.lang.ClassCastException ->
                        DeployXBundle.message("settings.config.import.invalidFile")
                    else ->
                        DeployXBundle.message("settings.config.import.failed", errorMsg)
                }
                Messages.showErrorDialog(
                    friendlyMessage,
                    DeployXBundle.message("settings.button.importConfig")
                )
                return
            }
        }
    }

    override fun isModified(): Boolean {
        return generalPanel.isModified() || serverPanel.isModified() || mappingPanel.isModified() || rsyncPanel.isModified() || scriptPanel.isModified()
    }

    override fun apply() {
        generalPanel.apply()
        serverPanel.apply()
        mappingPanel.apply()
        rsyncPanel.apply()
        scriptPanel.apply()
    }

    override fun reset() {
        generalPanel.reset()
        serverPanel.reset()
        mappingPanel.reset()
        rsyncPanel.reset()
        scriptPanel.reset()
    }

    override fun disposeUIResources() {
        langListenerRemover?.invoke()
        langListenerRemover = null
        mainPanel = null
        tabbedPane = null
    }
}
