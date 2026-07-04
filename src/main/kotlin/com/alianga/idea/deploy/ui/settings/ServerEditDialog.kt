package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent

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
    private val keyFileField = JBTextField()
    private val isDefaultCheck = JBCheckBox(DeployXBundle.message("dialog.server.checkbox.setAsDefault"))

    init {
        title = when {
            isCopyMode -> DeployXBundle.message("dialog.server.copy.title")
            existingServer != null -> DeployXBundle.message("dialog.server.edit.title")
            else -> DeployXBundle.message("dialog.server.add.title")
        }
        init()

        existingServer?.let { fillData(it, isCopyMode) }
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
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.password"), passwordField)
            .addLabeledComponent(DeployXBundle.message("dialog.server.label.keyFile"), keyFileField)
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
        return null
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
