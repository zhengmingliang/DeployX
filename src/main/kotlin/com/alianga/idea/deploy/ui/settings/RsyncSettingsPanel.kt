package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.alianga.idea.deploy.service.TransferService
import com.alianga.idea.deploy.util.RsyncDownloader
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
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

    /** rsync 检测结果，控制下载按钮可见性 */
    private var rsyncAvailable = false

    /** 一键下载安装 rsync 按钮（仅 Windows + rsync 不可用时显示） */
    private val downloadButton = JButton(DeployXBundle.message("settings.rsync.button.downloadRsync")).apply {
        isVisible = false
        addActionListener { startDownloadAndInstall() }
    }

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
            add(downloadButton)
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
            val rsyncOk = RsyncWrapper.isRsyncAvailable()
            val sshpassOk = RsyncWrapper.isSshpassAvailable()
            SwingUtilities.invokeLater {
                rsyncAvailable = rsyncOk
                if (rsyncOk) {
                    rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.rsync.available")
                    rsyncStatusLabel.foreground = java.awt.Color(0, 128, 0)
                } else {
                    rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.rsync.unavailable")
                    rsyncStatusLabel.foreground = java.awt.Color(200, 0, 0)
                }
                if (sshpassOk) {
                    sshpassStatusLabel.text = DeployXBundle.message("settings.rsync.sshpass.available")
                    sshpassStatusLabel.foreground = java.awt.Color(0, 128, 0)
                } else {
                    sshpassStatusLabel.text = DeployXBundle.message("settings.rsync.sshpass.unavailable")
                    sshpassStatusLabel.foreground = java.awt.Color(200, 0, 0)
                }
                // 仅 Windows + rsync 不可用时显示下载按钮
                downloadButton.isVisible = SystemInfo.isWindows && !rsyncOk
            }
        }.start()
    }

    /**
     * 一键下载安装 rsync（仅 Windows）。
     *
     * 后台下载 zip 并解压到 ~/.deploy-x/rsync-win/，成功后自动填充 rsyncPath 并重新检测；
     * 失败则弹出对话框给出手动下载链接。
     */
    private fun startDownloadAndInstall() {
        downloadButton.isEnabled = false
        rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.download.installing")
        rsyncStatusLabel.foreground = java.awt.Color(0, 0, 200)

        ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Downloading rsync...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val result = RsyncDownloader.downloadAndInstall { percent ->
                    indicator.fraction = percent / 100.0
                    indicator.text = "$percent%"
                }
                SwingUtilities.invokeLater {
                    downloadButton.isEnabled = true
                    result.fold(
                        onSuccess = { rsyncExe ->
                            val path = rsyncExe.absolutePath.replace('\\', '/')
                            rsyncPathField.text = path
                            rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.download.success", path)
                            rsyncStatusLabel.foreground = java.awt.Color(0, 128, 0)
                            // 立即持久化路径，避免用户忘记点 Apply
                            settings.rsyncPath = path
                            checkTools()
                        },
                        onFailure = { error ->
                            rsyncStatusLabel.text = DeployXBundle.message("settings.rsync.rsync.unavailable")
                            rsyncStatusLabel.foreground = java.awt.Color(200, 0, 0)
                            showDownloadFailedDialog(error.message ?: error.toString())
                        }
                    )
                }
            }
        })
    }

    /**
     * 下载失败时弹出对话框，提供手动下载链接。
     */
    private fun showDownloadFailedDialog(errorMsg: String) {
        val message = DeployXBundle.message("settings.rsync.download.failed", errorMsg)
        // 用 HTML 格式让链接可点击
        val htmlMessage = """
            <html><body style='width:480px'>
            ${message.replace("\n", "<br/>")}<br/><br/>
            <a href="${RsyncDownloader.GITHUB_RELEASES_PAGE}">${DeployXBundle.message("settings.rsync.download.githubLink")}</a><br/>
            <a href="${RsyncDownloader.MIRROR_DOWNLOAD_URL}">${DeployXBundle.message("settings.rsync.download.mirrorLink")}</a>
            </body></html>
        """.trimIndent()

        val dialog = javax.swing.JDialog()
        dialog.title = DeployXBundle.message("settings.rsync.download.failed", "").trim()
        dialog.isModal = true
        // 使用 Messages.showInputDialog 的替代方案：用 JEditorPane 支持超链接
        val editorPane = javax.swing.JEditorPane("text/html", htmlMessage).apply {
            isEditable = false
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    BrowserUtil.browse(e.url)
                }
            }
        }
        val scrollPane = javax.swing.JScrollPane(editorPane)
        scrollPane.preferredSize = java.awt.Dimension(520, 200)
        dialog.contentPane.add(scrollPane, BorderLayout.CENTER)
        val okButton = JButton("OK").apply { addActionListener { dialog.dispose() } }
        val buttonPanel = JPanel().apply { add(okButton) }
        dialog.contentPane.add(buttonPanel, BorderLayout.SOUTH)
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
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
