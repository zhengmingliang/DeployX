package com.alianga.idea.filesync.ui.settings

import com.alianga.idea.filesync.config.FileSyncSettings
import com.alianga.idea.filesync.ssh.RsyncWrapper
import com.alianga.idea.filesync.service.TransferService
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
    private val compressCheck = JBCheckBox("启用压缩传输 (-z)", settings.compress)
    private val showProgressCheck = JBCheckBox("显示传输进度 (--progress)", settings.showProgress)
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
        val infoLabel = JBLabel("<html><i>Transfer 配置。AUTO 模式优先 rsync，不可用时降级为 SFTP。</i></html>")

        val detectPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(rsyncStatusLabel)
            add(JButton("检测工具").apply { addActionListener { checkTools() } })
        }
        val sshpassPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sshpassStatusLabel)
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(infoLabel)
            .addVerticalGap(8)
            .addLabeledComponent("传输模式:", transferModeCombo)
            .addLabeledComponent("rsync 路径:", rsyncPathField)
            .addLabeledComponent("rsync 选项:", rsyncOptionsField)
            .addVerticalGap(4)
            .addComponent(compressCheck)
            .addComponent(showProgressCheck)
            .addVerticalGap(8)
            .addLabeledComponent("SSH 连接超时 (ms):", connectTimeoutField)
            .addVerticalGap(8)
            .addLabeledComponent("rsync 状态:", detectPanel)
            .addLabeledComponent("sshpass 状态:", sshpassPanel)
            .addVerticalGap(8)
            .addComponent(osHelpLabel)
            .panel

        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
        wrapper.add(formPanel)
        add(wrapper, BorderLayout.NORTH)
    }

    private fun checkTools() {
        rsyncStatusLabel.text = "检测中..."
        sshpassStatusLabel.text = "检测中..."
        Thread {
            val rsyncAvailable = RsyncWrapper.isRsyncAvailable()
            val sshpassAvailable = RsyncWrapper.isSshpassAvailable()
            SwingUtilities.invokeLater {
                if (rsyncAvailable) {
                    rsyncStatusLabel.text = "✓ rsync 可用"
                    rsyncStatusLabel.foreground = java.awt.Color(0, 128, 0)
                } else {
                    rsyncStatusLabel.text = "✗ rsync 不可用（AUTO 将使用 SFTP fallback）"
                    rsyncStatusLabel.foreground = java.awt.Color(200, 0, 0)
                }
                if (sshpassAvailable) {
                    sshpassStatusLabel.text = "✓ sshpass 可用（rsync 密码认证可用）"
                    sshpassStatusLabel.foreground = java.awt.Color(0, 128, 0)
                } else {
                    sshpassStatusLabel.text = "✗ sshpass 不可用（密码认证下 AUTO 将使用 SFTP fallback）"
                    sshpassStatusLabel.foreground = java.awt.Color(200, 0, 0)
                }
            }
        }.start()
    }

    private fun osHelpText(): String {
        val text = when {
            SystemInfo.isWindows -> "Windows: 默认通常没有 rsync/sshpass。建议使用 AUTO/SFTP_ONLY；如需 rsync，请安装 MSYS2/cwRsync/Git Bash 并配置 rsync.exe 路径。"
            SystemInfo.isMac -> "macOS: 密码认证通常没有 sshpass，AUTO 会降级 SFTP；如需新版 rsync 可 brew install rsync。"
            else -> "Linux: 推荐安装 sudo apt install rsync sshpass；未安装时 AUTO 可降级为 SFTP。"
        }
        return "<html><body style='width:520px'>$text<br/>SFTP fallback 不支持 rsync 增量算法和精确 dry-run。</body></html>"
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
