package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.ssh.SshConnection
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 服务器编辑对话框
 * @param existingServer 已有服务器配置（编辑/复制时传入）
 * @param isCopyMode 是否为复制模式（ID 可编辑，填充已有数据）
 */
class ServerEditDialog(
    private val existingServer: ServerConfig?,
    private val isCopyMode: Boolean = false
) : DialogWrapper(null) {

    private val idField = JBTextField()
    private val nameField = JBTextField()
    private val hostField = JBTextField()
    private val portField = JBTextField("22")
    private val userField = JBTextField()
    private val authTypeCombo = JComboBox(ServerConfig.AuthType.entries.toTypedArray())
    private val passwordField = JBPasswordField()
    private val keyFileField = TextFieldWithBrowseButton()
    private val isDefaultCheck = JBCheckBox(DeployXBundle.message("dialog.server.checkbox.setAsDefault"))

    // 认证方式联动：根据 authType 切换显示密码框 / 密钥文件框
    private val authFieldLabel = JBLabel(DeployXBundle.message("dialog.server.label.password"))
    private val authFieldPanel = JPanel(CardLayout())

    // 测试连接按钮
    private val testConnectionButton = JButton(
        DeployXBundle.message("dialog.server.action.testConnection"),
        AllIcons.Actions.Execute
    )

    init {
        title = when {
            isCopyMode -> DeployXBundle.message("dialog.server.copy.title")
            existingServer != null -> DeployXBundle.message("dialog.server.edit.title")
            else -> DeployXBundle.message("dialog.server.add.title")
        }

        // 卡片面板：密码 / 密钥文件
        authFieldPanel.add(passwordField, ServerConfig.AuthType.PASSWORD.name)
        authFieldPanel.add(keyFileField, ServerConfig.AuthType.KEY.name)

        // 密钥文件浏览按钮：允许手动输入，也允许通过文件选择器选择
        keyFileField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor()
                    .withTitle(DeployXBundle.message("dialog.server.browser.selectKeyFile"))
                    .withDescription(DeployXBundle.message("dialog.server.browser.selectKeyFile.desc")),
                null
            )
        )

        // 认证方式切换：联动显示对应输入框
        authTypeCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                updateAuthFieldVisibility()
            }
        }

        // 测试连接按钮
        testConnectionButton.addActionListener { testConnection() }

        init()

        existingServer?.let { fillData(it, isCopyMode) }
        // 编辑模式回填后，根据 authType 显示对应字段
        updateAuthFieldVisibility()
    }

    private fun updateAuthFieldVisibility() {
        val authType = authTypeCombo.selectedItem as ServerConfig.AuthType
        (authFieldPanel.layout as CardLayout).show(authFieldPanel, authType.name)
        authFieldLabel.text = if (authType == ServerConfig.AuthType.PASSWORD)
            DeployXBundle.message("dialog.server.label.password")
        else
            DeployXBundle.message("dialog.server.label.keyFile")
        authFieldPanel.revalidate()
        authFieldPanel.repaint()
    }

    private fun fillData(server: ServerConfig, copyMode: Boolean) {
        if (copyMode) {
            // 复制模式：ID 可编辑，默认加后缀
            idField.text = "${server.id}_copy"
            idField.isEnabled = true
        } else {
            // 编辑模式：ID 不可修改
            idField.text = server.id
            idField.isEnabled = false
        }
        nameField.text = if (copyMode) "${server.name} ${DeployXBundle.message("dialog.server.copy.suffix")}" else server.name
        hostField.text = server.host
        portField.text = server.port.toString()
        userField.text = server.user
        authTypeCombo.selectedItem = server.authType
        passwordField.text = server.password
        keyFileField.text = server.keyFile
        isDefaultCheck.isSelected = false // 复制时默认不设为默认
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.id"), idField)
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.name"), nameField)
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.host"), hostField)
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.port"), portField)
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.user"), userField)
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.authType"), authTypeCombo)
            .addLabeledComponent(authFieldLabel, authFieldPanel)
            .addComponent(testConnectionButton)
            .addComponent(isDefaultCheck)
            .panel

        panel.preferredSize = Dimension(400, 350)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (idField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.server.validation.idRequired"), idField)
        if (nameField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.server.validation.nameRequired"), nameField)
        if (hostField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.server.validation.hostRequired"), hostField)
        if (userField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.server.validation.userRequired"), userField)
        val port = portField.text.toIntOrNull()
        if (port == null || port < 1 || port > 65535) return ValidationInfo(DeployXBundle.message("dialog.server.validation.portInvalid"), portField)
        // 根据认证方式校验对应字段
        val authType = authTypeCombo.selectedItem as ServerConfig.AuthType
        if (authType == ServerConfig.AuthType.KEY && keyFileField.text.isBlank()) {
            return ValidationInfo(DeployXBundle.message("dialog.server.validation.keyFileRequired"), keyFileField)
        }
        return null
    }

    /**
     * 测试连接：使用当前对话框填写的临时配置进行 SSH 连接测试
     */
    private fun testConnection() {
        val validation = doValidate()
        if (validation != null) {
            Messages.showWarningDialog(
                validation.message,
                DeployXBundle.message("dialog.server.validation.title")
            )
            return
        }

        val server = getServerConfig()
        testConnectionButton.isEnabled = false

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                null,
                DeployXBundle.message("settings.server.connection.testing"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = DeployXBundle.message(
                        "settings.server.connection.connecting",
                        server.displayAddress
                    )
                    val connection = SshConnection(server)
                    val result = connection.testConnection()

                    ApplicationManager.getApplication().invokeLater {
                        if (result.success) {
                            Messages.showInfoMessage(
                                result.message,
                                DeployXBundle.message("settings.server.connection.success.title")
                            )
                        } else {
                            Messages.showErrorDialog(
                                result.message,
                                DeployXBundle.message("settings.server.connection.failed.title")
                            )
                        }
                    }
                }

                override fun onFinished() {
                    testConnectionButton.isEnabled = true
                }
            }
        )
    }

    fun getServerConfig(): ServerConfig {
        return ServerConfig(
            id = idField.text.trim(),
            name = nameField.text.trim(),
            host = hostField.text.trim(),
            port = portField.text.toIntOrNull() ?: 22,
            user = userField.text.trim(),
            authType = authTypeCombo.selectedItem as ServerConfig.AuthType,
            password = String(passwordField.password),
            keyFile = keyFileField.text.trim(),
            isDefault = isDefaultCheck.isSelected
        )
    }
}
