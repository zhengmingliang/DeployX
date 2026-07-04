package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ui.dialog.RemotePathChooserDialog
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * 映射编辑对话框
 */
class MappingEditDialog(
    private val existingMapping: MappingConfig?,
    private val isCopyMode: Boolean = false,
    private val prefillData: MappingConfig? = null
) : DialogWrapper(null) {

    private val nameField = JBTextField()
    private val localDirField = TextFieldWithBrowseButton()
    private val serverCombo = JComboBox<String>()
    private val remoteDirField = TextFieldWithBrowseButton()
    private val backupEnabledCheck = JBCheckBox(DeployXBundle.message("dialog.mapping.checkbox.enableBackup"))
    private val backupDirField = JBTextField()
    private val backupSourceField = JBTextField()
    private val unzipEnabledCheck = JBCheckBox(DeployXBundle.message("dialog.mapping.checkbox.enableUnzip"))
    private val unzipDestField = JBTextField()
    private val excludeField = JBTextField()
    private val preCommandEnabledCheck = JBCheckBox(DeployXBundle.message("dialog.mapping.checkbox.enablePreCommand"))
    private val preCommandField = JBTextField()
    private val postCommandEnabledCheck = JBCheckBox(DeployXBundle.message("dialog.mapping.checkbox.enablePostCommand"))
    private val postCommandField = JBTextField()
    private val autoCdCheck = JBCheckBox(DeployXBundle.message("dialog.mapping.checkbox.autoCd"), false)

    // 保存原始ID，编辑时保留，复制/新建时生成新ID
    private val mappingId: String

    init {
        title = when {
            isCopyMode -> DeployXBundle.message("dialog.mapping.copy.title")
            existingMapping != null -> DeployXBundle.message("dialog.mapping.edit.title")
            prefillData != null -> DeployXBundle.message("dialog.mapping.saveAs.title")
            else -> DeployXBundle.message("dialog.mapping.add.title")
        }

        mappingId = when {
            isCopyMode -> MappingConfig.generateId() // 复制时生成新ID
            existingMapping != null -> existingMapping.effectiveId // 编辑时保留原ID
            else -> MappingConfig.generateId() // 新建时生成新ID
        }

        init()

        setupServerCombo()
        setupLocalDirBrowser()
        setupRemoteDirBrowser()

        // 备份/解压启用状态联动
        backupEnabledCheck.addChangeListener {
            backupDirField.isEnabled = backupEnabledCheck.isSelected
            backupSourceField.isEnabled = backupEnabledCheck.isSelected
        }
        unzipEnabledCheck.addChangeListener {
            unzipDestField.isEnabled = unzipEnabledCheck.isSelected
        }
        preCommandEnabledCheck.addChangeListener {
            preCommandField.isEnabled = preCommandEnabledCheck.isSelected
        }
        postCommandEnabledCheck.addChangeListener {
            postCommandField.isEnabled = postCommandEnabledCheck.isSelected
        }
        backupDirField.isEnabled = false
        backupSourceField.isEnabled = false
        unzipDestField.isEnabled = false
        preCommandField.isEnabled = false
        postCommandField.isEnabled = false

        when {
            prefillData != null -> fillData(prefillData, nameEditable = true)
            existingMapping != null -> fillData(existingMapping, nameEditable = isCopyMode)
        }
    }

    private fun setupServerCombo() {
        val servers = ServerManager.getInstance().getServers()
        for (server in servers) {
            serverCombo.addItem("${server.id} - ${server.name}")
        }
    }

    private fun setupLocalDirBrowser() {
        localDirField.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(DeployXBundle.message("dialog.mapping.browser.selectLocal"))
                .withDescription(DeployXBundle.message("dialog.mapping.browser.selectLocal.desc"))
        )
    }

    private fun setupRemoteDirBrowser() {
        remoteDirField.addActionListener {
            // 获取当前选中的服务器
            val selectedServerStr = serverCombo.selectedItem?.toString() ?: return@addActionListener
            val serverId = selectedServerStr.substringBefore(" - ")
            val server = ServerManager.getInstance().getServer(serverId) ?: return@addActionListener

            // 打开远程路径选择对话框
            val currentPath = remoteDirField.text.trim().ifBlank { "/" }
            val dialog = RemotePathChooserDialog(server, currentPath)
            if (dialog.showAndGet()) {
                remoteDirField.text = dialog.getSelectedPath()
            }
        }
        // 设置工具提示
        remoteDirField.toolTipText = DeployXBundle.message("dialog.mapping.tooltip.remotePathBrowse")
    }

    private fun fillData(mapping: MappingConfig, nameEditable: Boolean) {
        nameField.text = mapping.name
        nameField.isEnabled = nameEditable
        localDirField.text = mapping.localDir
        remoteDirField.text = mapping.remoteDir

        // 备份配置
        backupEnabledCheck.isSelected = mapping.backupEnabled
        backupDirField.text = mapping.backupDir
        backupDirField.isEnabled = mapping.backupEnabled
        backupSourceField.text = mapping.backupSource
        backupSourceField.isEnabled = mapping.backupEnabled

        // 解压配置
        unzipEnabledCheck.isSelected = mapping.unzipEnabled
        unzipDestField.text = mapping.unzipDest
        unzipDestField.isEnabled = mapping.unzipEnabled

        excludeField.text = mapping.exclude.joinToString(", ")

        // 上传前/后命令启用状态（兼容旧配置）
        preCommandEnabledCheck.isSelected = mapping.effectivePreCommandEnabled
        postCommandEnabledCheck.isSelected = mapping.effectivePostCommandEnabled
        preCommandField.isEnabled = mapping.effectivePreCommandEnabled
        postCommandField.isEnabled = mapping.effectivePostCommandEnabled

        // 检测自动 cd 模式
        val remoteDir = mapping.remoteDir
        val cdPrefix = "cd $remoteDir && "
        if (mapping.preCommand.startsWith(cdPrefix)) {
            autoCdCheck.isSelected = true
            preCommandField.text = mapping.preCommand.removePrefix(cdPrefix)
        } else {
            autoCdCheck.isSelected = false
            preCommandField.text = mapping.preCommand
        }
        if (mapping.postCommand.startsWith(cdPrefix)) {
            autoCdCheck.isSelected = true
            postCommandField.text = mapping.postCommand.removePrefix(cdPrefix)
        } else {
            postCommandField.text = mapping.postCommand
        }

        val servers = ServerManager.getInstance().getServers()
        val index = servers.indexOfFirst { it.id == mapping.serverId }
        if (index >= 0) {
            serverCombo.selectedIndex = index
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.name"), nameField)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.localDir"), localDirField)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.targetServer"), serverCombo)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.remoteDir"), remoteDirField)
            .addVerticalGap(8)
            .addComponent(backupEnabledCheck)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.backupDirectory"), backupDirField)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.backupSource"), backupSourceField)
            .addVerticalGap(8)
            .addComponent(unzipEnabledCheck)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.unzipDestination"), unzipDestField)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.excludeRules"), excludeField)
            .addVerticalGap(8)
            .addComponent(preCommandEnabledCheck)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.preUploadCommand"), preCommandField)
            .addComponent(postCommandEnabledCheck)
            .addLabeledComponent(DeployXBundle.message("dialog.mapping.label.postUploadCommand"), postCommandField)
            .addComponent(autoCdCheck)
            .panel

        panel.preferredSize = Dimension(520, 600)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.validation.nameRequired"), nameField)
        if (localDirField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.validation.localDirRequired"), localDirField)
        if (serverCombo.selectedItem == null) return ValidationInfo(DeployXBundle.message("dialog.validation.selectTargetServer"), serverCombo)
        if (remoteDirField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.validation.remoteDirRequired"), remoteDirField)
        if (backupEnabledCheck.isSelected && backupDirField.text.isBlank()) {
            return ValidationInfo(DeployXBundle.message("dialog.validation.backupDirRequired"), backupDirField)
        }
        if (unzipEnabledCheck.isSelected && unzipDestField.text.isBlank()) {
            return ValidationInfo(DeployXBundle.message("dialog.validation.unzipDestRequired"), unzipDestField)
        }
        return null
    }

    fun getMappingConfig(): MappingConfig {
        val selectedServer = serverCombo.selectedItem?.toString() ?: ""
        val serverId = selectedServer.substringBefore(" - ")
        val remoteDir = remoteDirField.text.trim()

        val rawPreCommand = preCommandField.text.trim()
        val rawPostCommand = postCommandField.text.trim()
        val preCommand = if (autoCdCheck.isSelected && rawPreCommand.isNotBlank()) {
            "cd $remoteDir && $rawPreCommand"
        } else {
            rawPreCommand
        }
        val postCommand = if (autoCdCheck.isSelected && rawPostCommand.isNotBlank()) {
            "cd $remoteDir && $rawPostCommand"
        } else {
            rawPostCommand
        }

        return MappingConfig(
            id = mappingId,
            name = nameField.text.trim(),
            localDir = localDirField.text.trim(),
            serverId = serverId,
            remoteDir = remoteDir,
            backupEnabled = backupEnabledCheck.isSelected,
            backupDir = backupDirField.text.trim(),
            backupSource = backupSourceField.text.trim(),
            unzipEnabled = unzipEnabledCheck.isSelected,
            unzipDest = unzipDestField.text.trim(),
            exclude = excludeField.text.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            preCommandEnabled = preCommandEnabledCheck.isSelected,
            preCommand = preCommand,
            postCommandEnabled = postCommandEnabledCheck.isSelected,
            postCommand = postCommand
        )
    }
}
