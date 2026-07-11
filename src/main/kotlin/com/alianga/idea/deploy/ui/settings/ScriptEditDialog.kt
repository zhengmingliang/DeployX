package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptParam
import com.alianga.idea.deploy.service.ServerManager
import com.alianga.idea.deploy.ui.ScriptEditorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.table.AbstractTableModel

/**
 * 脚本编辑对话框。
 */
class ScriptEditDialog(
    private val existingScript: ScriptConfig?,
    private val isCopyMode: Boolean = false
) : DialogWrapper(null) {

    private val nameField = JBTextField()
    private val descriptionField = JBTextArea(3, 40)
    private val groupField = JBTextField(DeployXBundle.message("dialog.script.defaultGroup"))
    private val tagsField = JBTextField()
    private val serverCombo = JComboBox<String>()
    private val commandArea: EditorTextField = ScriptEditorFactory.createEditable("")
    private val workingDirField = JBTextField()
    private val autoCdCheck = JBCheckBox(DeployXBundle.message("dialog.script.checkbox.autoCd"), false)
    private val confirmCheck = JBCheckBox(DeployXBundle.message("dialog.script.checkbox.confirmBeforeRun"), true)
    private val timeoutField = JBTextField("300")
    private val dangerousKeywordsField = JBTextField(ScriptConfig.DEFAULT_DANGEROUS_KEYWORDS.joinToString(", "))

    private val paramModel = ParamTableModel()
    private val paramTable = JBTable(paramModel)
    private val scriptId: String
    private val createdAt: Long

    init {
        title = when {
            isCopyMode -> DeployXBundle.message("dialog.script.copy.title")
            existingScript != null -> DeployXBundle.message("dialog.script.edit.title")
            else -> DeployXBundle.message("dialog.script.add.title")
        }
        scriptId = if (existingScript != null && !isCopyMode) existingScript.id else ScriptConfig.generateId()
        createdAt = if (existingScript != null && !isCopyMode) existingScript.createdAt else System.currentTimeMillis()

        setupServerCombo()
        existingScript?.let { fillData(it) }
        if (isCopyMode) nameField.text = "${nameField.text} ${DeployXBundle.message("dialog.script.copy.suffix")}"

        descriptionField.lineWrap = true
        descriptionField.wrapStyleWord = true
        init()
    }

    private fun setupServerCombo() {
        serverCombo.addItem(DeployXBundle.message("dialog.script.placeholder.serverSelect"))
        ServerManager.getInstance().getServers().forEach { server ->
            serverCombo.addItem("${server.id} - ${server.name} (${server.displayAddress})")
        }
        serverCombo.preferredSize = Dimension(520, serverCombo.preferredSize.height)
        serverCombo.minimumSize = Dimension(360, serverCombo.minimumSize.height)
    }

    private fun fillData(script: ScriptConfig) {
        nameField.text = script.name
        descriptionField.text = script.description
        groupField.text = script.group
        tagsField.text = script.tags.joinToString(", ")
        commandArea.text = script.command
        workingDirField.text = script.workingDir
        autoCdCheck.isSelected = script.autoCdRemoteDir
        confirmCheck.isSelected = script.confirmBeforeRun
        timeoutField.text = script.timeoutSec.toString()
        dangerousKeywordsField.text = script.dangerousKeywords.joinToString(", ")
        paramModel.setData(script.params)

        val servers = ServerManager.getInstance().getServers()
        val index = servers.indexOfFirst { it.id == script.serverId }
        serverCombo.selectedIndex = if (index >= 0) index + 1 else 0
    }

    override fun createCenterPanel(): JComponent {
        val commandScroll = commandArea.apply {
            preferredSize = Dimension(640, 240)
        }
        val variablesArea = JBTextArea(buildVariablesHelpText(), 8, 60).apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }
        val variablesScroll = JBScrollPane(variablesArea).apply {
            preferredSize = Dimension(640, 150)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        val descriptionScroll = JBScrollPane(descriptionField).apply {
            preferredSize = Dimension(640, 72)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        val contentPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.name"), nameField)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.description"), descriptionScroll)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.group"), groupField)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.tags"), tagsField)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.bindServer"), serverCombo)
            .addVerticalGap(8)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.commandTemplate"), commandScroll)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.variableHelp"), variablesScroll)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.workingDir"), workingDirField)
            .addComponent(autoCdCheck)
            .addComponent(confirmCheck)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.timeout"), timeoutField)
            .addLabeledComponent(DeployXBundle.message("dialog.script.label.dangerousKeywords"), dangerousKeywordsField)
            .panel

        val decorator = ToolbarDecorator.createDecorator(paramTable)
            .setAddAction { addParam() }
            .setEditAction { editParam() }
            .setRemoveAction { removeParam() }
        val paramsPanel = JPanel(BorderLayout()).apply {
            add(decorator.createPanel(), BorderLayout.CENTER)
        }

        return JBTabbedPane().apply {
            addTab(DeployXBundle.message("dialog.script.tabs.scriptContent"), JBScrollPane(contentPanel).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER })
            addTab(DeployXBundle.message("dialog.script.tabs.paramDefinition"), paramsPanel)
            preferredSize = Dimension(780, 720)
        }
    }

    private fun buildVariablesHelpText(): String {
        return buildString {
            appendLine(DeployXBundle.message("dialog.script.variables.paramHeader"))
            appendLine("  \${'$'}{server.id} - server ID")
            appendLine("  \${'$'}{server.name} - server name")
            appendLine("  \${'$'}{server.host} - server host/IP")
            appendLine("  \${'$'}{server.port} - SSH port")
            appendLine("  \${'$'}{server.user} - SSH username")
            appendLine("  \${'$'}{server.address} - user@host:port display address")
            appendLine("  \${'$'}{server.auth} - auth type password/key")
            appendLine("  \${'$'}{mapping.id} - current directory mapping ID")
            appendLine("  \${'$'}{mapping.name} - current directory mapping name")
            appendLine("  \${'$'}{mapping.localDir} - mapping local root directory")
            appendLine("  \${'$'}{mapping.remoteDir} - mapping remote root directory")
            appendLine("  \${'$'}{mapping.serverId} - mapping bound server ID")
            appendLine("  \${'$'}{path.remoteDir} - currently resolved remote directory")
            appendLine("  \${'$'}{path.projectBase} - current project root directory")
            appendLine("  \${'$'}{path.artifact} - build artifact path, empty if not provided")
            appendLine("  \${'$'}{path.local} - first selected local path")
            appendLine("  \${'$'}{path.locals} - all selected local paths")
            appendLine("  \${'$'}{path.local.0} - 1st local path, path.local.1 for 2nd, etc.")
            appendLine()
            appendLine(DeployXBundle.message("dialog.script.variables.hint"))
        }.trimEnd()
    }

    private fun addParam() {
        val dialog = ScriptParamEditDialog(null)
        if (dialog.showAndGet()) paramModel.addParam(dialog.getParam())
    }

    private fun editParam() {
        val row = paramTable.selectedRow
        if (row < 0) return
        val param = paramModel.getParamAt(row) ?: return
        val dialog = ScriptParamEditDialog(param)
        if (dialog.showAndGet()) paramModel.updateParam(row, dialog.getParam())
    }

    private fun removeParam() {
        val row = paramTable.selectedRow
        if (row >= 0) paramModel.removeParam(row)
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.script.validation.nameRequired"), nameField)
        if (commandArea.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.script.validation.commandRequired"), commandArea)
        val timeout = timeoutField.text.trim().toIntOrNull()
        if (timeout == null || timeout <= 0) return ValidationInfo(DeployXBundle.message("dialog.script.validation.timeoutInvalid"), timeoutField)
        val names = mutableSetOf<String>()
        paramModel.getData().forEach { param ->
            if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(param.name)) {
                return ValidationInfo(DeployXBundle.message("dialog.script.validation.paramNameInvalid"), paramTable)
            }
            if (!names.add(param.name)) return ValidationInfo(DeployXBundle.message("dialog.script.validation.duplicateParamName", param.name), paramTable)
            if (param.type == ScriptParam.ParamType.ENUM && param.options.isEmpty()) {
                return ValidationInfo(DeployXBundle.message("dialog.script.validation.enumParamRequiresOptions", param.name), paramTable)
            }
        }
        return null
    }

    fun getScriptConfig(): ScriptConfig {
        val selectedServer = serverCombo.selectedItem?.toString().orEmpty()
        val serverId = selectedServer.substringBefore(" - ").trim()
        return ScriptConfig(
            id = scriptId,
            name = nameField.text.trim(),
            description = descriptionField.text.trim(),
            group = groupField.text.trim().ifBlank { DeployXBundle.message("dialog.script.defaultGroup") },
            tags = tagsField.text.split(",").map { it.trim() }.filter { it.isNotBlank() },
            serverId = serverId,
            command = commandArea.text.trim(),
            params = paramModel.getData(),
            workingDir = workingDirField.text.trim(),
            autoCdRemoteDir = autoCdCheck.isSelected,
            confirmBeforeRun = confirmCheck.isSelected,
            timeoutSec = timeoutField.text.trim().toIntOrNull() ?: 300,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis(),
            dangerousKeywords = dangerousKeywordsField.text.split(",").map { it.trim() }.filter { it.isNotBlank() }
        )
    }

    private class ParamTableModel : AbstractTableModel() {
        private val columns = arrayOf(
            DeployXBundle.message("dialog.param.table.name"),
            DeployXBundle.message("dialog.param.table.label"),
            DeployXBundle.message("dialog.param.table.type"),
            DeployXBundle.message("dialog.param.table.required"),
            DeployXBundle.message("dialog.param.table.default"),
            DeployXBundle.message("dialog.param.table.options")
        )
        private val params = mutableListOf<ScriptParam>()

        fun setData(data: List<ScriptParam>) {
            params.clear()
            params.addAll(data)
            fireTableDataChanged()
        }

        fun getData(): List<ScriptParam> = params.toList()
        fun getParamAt(row: Int): ScriptParam? = params.getOrNull(row)
        fun addParam(param: ScriptParam) { params.add(param); fireTableDataChanged() }
        fun updateParam(row: Int, param: ScriptParam) { params[row] = param; fireTableDataChanged() }
        fun removeParam(row: Int) { params.removeAt(row); fireTableDataChanged() }

        override fun getRowCount(): Int = params.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val param = params[rowIndex]
            return when (columnIndex) {
                0 -> param.name
                1 -> param.displayLabel
                2 -> param.type.name
                3 -> if (param.required) "✓" else ""
                4 -> param.defaultValue
                5 -> param.options.joinToString(",")
                else -> ""
            }
        }
    }
}

private class ScriptParamEditDialog(private val existingParam: ScriptParam?) : DialogWrapper(null) {
    private val nameField = JBTextField()
    private val labelField = JBTextField()
    private val typeCombo = JComboBox(ScriptParam.ParamType.entries.toTypedArray())
    private val requiredCheck = JBCheckBox(DeployXBundle.message("dialog.param.checkbox.required"))
    private val defaultField = JBTextField()
    private val optionsField = JBTextField()
    private val descriptionField = JBTextArea(3, 30)

    init {
        title = if (existingParam == null) DeployXBundle.message("dialog.param.add.title") else DeployXBundle.message("dialog.param.edit.title")
        existingParam?.let {
            nameField.text = it.name
            labelField.text = it.label
            typeCombo.selectedItem = it.type
            requiredCheck.isSelected = it.required
            defaultField.text = it.defaultValue
            optionsField.text = it.options.joinToString(", ")
            descriptionField.text = it.description
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        panel.add(
            FormBuilder.createFormBuilder()
                .addLabeledComponent(DeployXBundle.message("dialog.param.label.name"), nameField)
                .addLabeledComponent(DeployXBundle.message("dialog.param.label.displayLabel"), labelField)
                .addLabeledComponent(DeployXBundle.message("dialog.param.label.type"), typeCombo)
                .addComponent(requiredCheck)
                .addLabeledComponent(DeployXBundle.message("dialog.param.label.defaultValue"), defaultField)
                .addLabeledComponent(DeployXBundle.message("dialog.param.label.enumOptions"), optionsField)
                .addLabeledComponent(DeployXBundle.message("dialog.param.label.description"), JBScrollPane(descriptionField))
                .panel
        )
        panel.add(Box.createVerticalStrut(4))
        panel.preferredSize = Dimension(460, 320)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo(DeployXBundle.message("dialog.param.validation.nameRequired"), nameField)
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(nameField.text.trim())) {
            return ValidationInfo(DeployXBundle.message("dialog.param.validation.nameFormat"), nameField)
        }
        if (typeCombo.selectedItem == ScriptParam.ParamType.ENUM && optionsField.text.split(",").none { it.trim().isNotBlank() }) {
            return ValidationInfo(DeployXBundle.message("dialog.param.validation.enumRequiresOptions"), optionsField)
        }
        return null
    }

    fun getParam(): ScriptParam = ScriptParam(
        name = nameField.text.trim(),
        label = labelField.text.trim(),
        type = typeCombo.selectedItem as ScriptParam.ParamType,
        required = requiredCheck.isSelected,
        defaultValue = defaultField.text.trim(),
        options = optionsField.text.split(",").map { it.trim() }.filter { it.isNotBlank() },
        description = descriptionField.text.trim()
    )
}
