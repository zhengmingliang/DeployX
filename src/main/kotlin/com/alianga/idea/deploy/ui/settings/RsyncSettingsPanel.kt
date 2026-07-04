package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.alianga.idea.deploy.service.TransferService
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * rsync / Transfer 配置设置面板
 */
class RsyncSettingsPanel : JPanel(BorderLayout()) {

    private val settings = FileSyncSettings.getInstance()

    private val transferModeCombo = JComboBox(arrayOf("AUTO", "RSYNC_ONLY", "SFTP_ONLY"))
    private val rsyncPathField = JBTextField(settings.rsyncPath)
    private val rsyncOptionsField = JBTextField(settings.rsyncOptions)
    private val compressCheck = JBCheckBox(DeployXBundle.message("settings.rsync.compress"), settings.compress)
    private val showProgressCheck = JBCheckBox(DeployXBundle.message("settings.rsync.showProgress"), settings.showProgress)
    private val connectTimeoutField = JBTextField(settings.connectTimeout.toString())
    private val rsyncStatusLabel = JBLabel("")
    private val sshpassStatusLabel = JBLabel("")
    private val osHelpLabel = JBLabel(osHelpText())

    init {
        transferModeCombo.selectedItem = settings.transferMode
        setupUI()
        checkTools()
    }

    private fun setupUI() {
        val infoLabel = JBLabel("<html><i>${DeployXBundle.message("settings.rsync.info")}</i></html>")

        val detectPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(rsyncStatusLabel)
            add(JButton(DeployXBundle.message("settings.rsync.button.detectTools")).apply { addActionListener { checkTools() } })
        }
        val sshpassPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sshpassStatusLabel)
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(infoLabel)
            .addVerticalGap(8)
            .addLabeledComponent(DeployXBundle.message("settings.rsync.transferMode"), transferModeCombo)
            .addLabeledComponent(DeployXBundle.message("settings.rsync.rsyncPath"), rsyncPathField)
            .addLabeledComponent(DeployXBundle.message("settings.rsync.rsyncOptions"), rsyncOptionsField)
            .addVerticalGap(4)
            .addComponent(compressCheck)
            .addComponent(showProgressCheck)
            .addVerticalGap(8)
            .addLabeledComponent(DeployXBundle.message("settings.rsync.connectTimeout"), connectTimeoutField)
            .addVerticalGap(8)
            .addLabeledComponent(DeployXBundle.message("settings.rsync.status.rsync"), detectPanel)
            .addLabeledComponent(DeployXBundle.message("settings.rsync.status.sshpass"), sshpassPanel)
            .addVerticalGap(8)
            .addComponent(osHelpLabel)
            .panel

        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
        wrapper.add(formPanel)
        add(wrapper, BorderLayout.NORTH)
    }

    private fun checkTools() {
        rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.detecting")
        sshpassStatusLabel.text = DeployXBundle.message("settings.rsync.detecting")
        Thread {
            val rsyncAvailable = RsyncWrapper.isRsyncAvailable()
            val sshpassAvailable = RsyncWrapper.isSshpassAvailable()
            SwingUtilities.invokeLater {
                if (rsyncAvailable) {
                    rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.rsync.available")
                    rsyncStatusLabel.foreground = java.awt.Color(0, 128, 0)
                } else {
                    rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.rsync.unavailable")
                    rsyncStatusLabel.foreground = java.awt.Color(200, 0, 0)
                }
                if (sshpassAvailable) {
                    sshpassStatusLabel.text = DeployXBundle.message("settings.rsync.sshpass.available")
                    sshpassStatusLabel.foreground = java.awt.Color(0, 128, 0)
                } else {
                    sshpassStatusLabel.text = DeployXBundle.message("settings.rsync.sshpass.unavailable")
                    sshpassStatusLabel.foreground = java.awt.Color(200, 0, 0)
                }
            }
        }.start()
    }

    private fun osHelpText(): String {
        val text = when {
            SystemInfo.isWindows -> DeployXBundle.message("settings.rsync.help.windows")
            SystemInfo.isMac -> DeployXBundle.message("settings.rsync.help.macos")
            else -> DeployXBundle.message("settings.rsync.help.linux")
        }
        return "<html><body style='width:520px'>$text<br/>${DeployXBundle.message("settings.rsync.help.sftpNote")}</body></html>"
    }

    fun isModified(): Boolean {
        return transferModeCombo.selectedItem != settings.transferMode ||
                rsyncPathField.text != settings.rsyncPath ||
                rsyncOptionsField.text != settings.rsyncOptions ||
                compressCheck.isSelected != settings.compress ||
                showProgressCheck.isSelected != settings.showProgress ||
                connectTimeoutField.text.toIntOrNull() != settings.connectTimeout
    }

    fun apply() {
        settings.transferMode = transferModeCombo.selectedItem?.toString() ?: TransferService.TransferMode.AUTO.name
        settings.rsyncPath = rsyncPathField.text.trim()
        settings.rsyncOptions = rsyncOptionsField.text.trim()
        settings.compress = compressCheck.isSelected
        settings.showProgress = showProgressCheck.isSelected
        settings.connectTimeout = connectTimeoutField.text.toIntOrNull() ?: 10000
        settings.sshpassAvailable = RsyncWrapper.isSshpassAvailable()
    }

    fun reset() {
        transferModeCombo.selectedItem = settings.transferMode
        rsyncPathField.text = settings.rsyncPath
        rsyncOptionsField.text = settings.rsyncOptions
        compressCheck.isSelected = settings.compress
        showProgressCheck.isSelected = settings.showProgress
        connectTimeoutField.text = settings.connectTimeout.toString()
    }
}
