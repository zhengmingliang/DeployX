package com.alianga.idea.filesync.ui.settings

import com.alianga.idea.filesync.config.FileSyncSettings
import com.alianga.idea.filesync.ssh.RsyncWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * rsync 配置设置面板
 */
class RsyncSettingsPanel : JPanel(BorderLayout()) {

    private val settings = FileSyncSettings.getInstance()

    private val rsyncPathField = JBTextField(settings.rsyncPath)
    private val rsyncOptionsField = JBTextField(settings.rsyncOptions)
    private val compressCheck = JBCheckBox("启用压缩传输 (-z)", settings.compress)
    private val showProgressCheck = JBCheckBox("显示传输进度 (--progress)", settings.showProgress)
    private val connectTimeoutField = JBTextField(settings.connectTimeout.toString())
    private val sshpassStatusLabel = JBLabel("")

    init {
        setupUI()
        checkSshpass()
    }

    private fun setupUI() {
        val infoLabel = JBLabel("<html><i>rsync 命令配置。修改后点击 Apply 生效。</i></html>")

        val sshpassPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sshpassStatusLabel)
            add(JButton("检测").apply {
                addActionListener { checkSshpass() }
            })
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(infoLabel)
            .addVerticalGap(8)
            .addLabeledComponent("rsync 路径:", rsyncPathField)
            .addLabeledComponent("rsync 选项:", rsyncOptionsField)
            .addVerticalGap(4)
            .addComponent(compressCheck)
            .addComponent(showProgressCheck)
            .addVerticalGap(8)
            .addLabeledComponent("SSH 连接超时 (ms):", connectTimeoutField)
            .addVerticalGap(8)
            .addLabeledComponent("sshpass 状态:", sshpassPanel)
            .panel

        val wrapper = JPanel()
        wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
        wrapper.add(formPanel)

        add(wrapper, BorderLayout.NORTH)
    }

    private fun checkSshpass() {
        sshpassStatusLabel.text = "检测中..."
        Thread {
            val available = RsyncWrapper.isSshpassAvailable()
            SwingUtilities.invokeLater {
                if (available) {
                    sshpassStatusLabel.text = "✓ sshpass 可用（支持密码认证）"
                    sshpassStatusLabel.foreground = java.awt.Color(0, 128, 0)
                } else {
                    sshpassStatusLabel.text = "✗ sshpass 不可用（密码认证将失败，请安装 sshpass）"
                    sshpassStatusLabel.foreground = java.awt.Color(200, 0, 0)
                }
            }
        }.start()
    }

    fun isModified(): Boolean {
        return rsyncPathField.text != settings.rsyncPath ||
                rsyncOptionsField.text != settings.rsyncOptions ||
                compressCheck.isSelected != settings.compress ||
                showProgressCheck.isSelected != settings.showProgress ||
                connectTimeoutField.text.toIntOrNull() != settings.connectTimeout
    }

    fun apply() {
        settings.rsyncPath = rsyncPathField.text.trim()
        settings.rsyncOptions = rsyncOptionsField.text.trim()
        settings.compress = compressCheck.isSelected
        settings.showProgress = showProgressCheck.isSelected
        settings.connectTimeout = connectTimeoutField.text.toIntOrNull() ?: 10000
        settings.sshpassAvailable = RsyncWrapper.isSshpassAvailable()
    }

    fun reset() {
        rsyncPathField.text = settings.rsyncPath
        rsyncOptionsField.text = settings.rsyncOptions
        compressCheck.isSelected = settings.compress
        showProgressCheck.isSelected = settings.showProgress
        connectTimeoutField.text = settings.connectTimeout.toString()
    }
}
