package com.alianga.idea.filesync.ui.settings

import com.alianga.idea.filesync.model.MappingConfig
import com.alianga.idea.filesync.service.ServerManager
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
    private val remoteDirField = JBTextField()
    private val backupEnabledCheck = JBCheckBox("启用部署前备份")
    private val backupDirField = JBTextField()
    private val backupSourceField = JBTextField()
    private val unzipEnabledCheck = JBCheckBox("启用上传后解压")
    private val unzipDestField = JBTextField()
    private val excludeField = JBTextField()
    private val preCommandEnabledCheck = JBCheckBox("启用上传前命令")
    private val preCommandField = JBTextField()
    private val postCommandEnabledCheck = JBCheckBox("启用上传后命令")
    private val postCommandField = JBTextField()
    private val autoCdCheck = JBCheckBox("命令自动切换到远程目录 (cd <远程目录> && ...)", false)

    // 保存原始ID，编辑时保留，复制/新建时生成新ID
    private val mappingId: String

    init {
        title = when {
            isCopyMode -> "复制映射"
            existingMapping != null -> "编辑映射"
            prefillData != null -> "保存为映射"
            else -> "添加映射"
        }

        mappingId = when {
            isCopyMode -> MappingConfig.generateId() // 复制时生成新ID
            existingMapping != null -> existingMapping.effectiveId // 编辑时保留原ID
            else -> MappingConfig.generateId() // 新建时生成新ID
        }

        init()

        setupServerCombo()
        setupLocalDirBrowser()

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
            "选择本地目录",
            "选择要同步的本地目录",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
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
            .addLabeledComponent("名称:", nameField)
            .addLabeledComponent("本地目录:", localDirField)
            .addLabeledComponent("目标服务器:", serverCombo)
            .addLabeledComponent("远程目录:", remoteDirField)
            .addVerticalGap(8)
            .addComponent(backupEnabledCheck)
            .addLabeledComponent("备份目录:", backupDirField)
            .addLabeledComponent("备份源 (可选):", backupSourceField)
            .addVerticalGap(8)
            .addComponent(unzipEnabledCheck)
            .addLabeledComponent("解压目标:", unzipDestField)
            .addLabeledComponent("排除规则 (逗号分隔):", excludeField)
            .addVerticalGap(8)
            .addComponent(preCommandEnabledCheck)
            .addLabeledComponent("上传前命令:", preCommandField)
            .addComponent(postCommandEnabledCheck)
            .addLabeledComponent("上传后命令:", postCommandField)
            .addComponent(autoCdCheck)
            .panel

        panel.preferredSize = Dimension(520, 600)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("名称不能为空", nameField)
        if (localDirField.text.isBlank()) return ValidationInfo("本地目录不能为空", localDirField)
        if (serverCombo.selectedItem == null) return ValidationInfo("请选择目标服务器", serverCombo)
        if (remoteDirField.text.isBlank()) return ValidationInfo("远程目录不能为空", remoteDirField)
        if (backupEnabledCheck.isSelected && backupDirField.text.isBlank()) {
            return ValidationInfo("启用了备份但未填写备份目录", backupDirField)
        }
        if (unzipEnabledCheck.isSelected && unzipDestField.text.isBlank()) {
            return ValidationInfo("启用了但未填写解压目标", unzipDestField)
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
