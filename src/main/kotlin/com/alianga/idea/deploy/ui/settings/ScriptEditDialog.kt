package com.alianga.idea.deploy.ui.settings

import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptParam
import com.alianga.idea.deploy.service.ServerManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
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
    private val groupField = JBTextField("默认")
    private val tagsField = JBTextField()
    private val serverCombo = JComboBox<String>()
    private val commandArea = JBTextArea(14, 80)
    private val workingDirField = JBTextField()
    private val autoCdCheck = JBCheckBox("自动切换到上下文远程目录", false)
    private val confirmCheck = JBCheckBox("执行前确认", true)
    private val timeoutField = JBTextField("300")
    private val dangerousKeywordsField = JBTextField(ScriptConfig.DEFAULT_DANGEROUS_KEYWORDS.joinToString(", "))

    private val paramModel = ParamTableModel()
    private val paramTable = JBTable(paramModel)
    private val scriptId: String
    private val createdAt: Long

    init {
        title = when {
            isCopyMode -> "复制脚本"
            existingScript != null -> "编辑脚本"
            else -> "添加脚本"
        }
        scriptId = if (existingScript != null && !isCopyMode) existingScript.id else ScriptConfig.generateId()
        createdAt = if (existingScript != null && !isCopyMode) existingScript.createdAt else System.currentTimeMillis()

        setupServerCombo()
        existingScript?.let { fillData(it) }
        if (isCopyMode) nameField.text = "${nameField.text} Copy"

        descriptionField.lineWrap = true
        descriptionField.wrapStyleWord = true
        commandArea.font = Font("Monospaced", Font.PLAIN, 12)
        commandArea.lineWrap = true
        commandArea.wrapStyleWord = false
        init()
    }

    private fun setupServerCombo() {
        serverCombo.addItem(" - 运行时选择/使用上下文")
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
        val commandScroll = JBScrollPane(commandArea).apply {
            preferredSize = Dimension(640, 240)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
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
            .addLabeledComponent("名称:", nameField)
            .addLabeledComponent("描述:", descriptionScroll)
            .addLabeledComponent("分组:", groupField)
            .addLabeledComponent("标签(逗号分隔):", tagsField)
            .addLabeledComponent("绑定服务器:", serverCombo)
            .addVerticalGap(8)
            .addLabeledComponent("命令模板:", commandScroll)
            .addLabeledComponent("变量说明:", variablesScroll)
            .addLabeledComponent("工作目录:", workingDirField)
            .addComponent(autoCdCheck)
            .addComponent(confirmCheck)
            .addLabeledComponent("超时(秒):", timeoutField)
            .addLabeledComponent("危险关键字:", dangerousKeywordsField)
            .panel

        val decorator = ToolbarDecorator.createDecorator(paramTable)
            .setAddAction { addParam() }
            .setEditAction { editParam() }
            .setRemoveAction { removeParam() }
        val paramsPanel = JPanel(BorderLayout()).apply {
            add(decorator.createPanel(), BorderLayout.CENTER)
        }

        return JBTabbedPane().apply {
            addTab("脚本内容", JBScrollPane(contentPanel).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER })
            addTab("参数定义", paramsPanel)
            preferredSize = Dimension(780, 720)
        }
    }

    private fun buildVariablesHelpText(): String {
        return buildString {
            appendLine("脚本参数变量:")
            appendLine("  在“参数定义”页添加参数后，可在命令中使用 ${'$'}{参数名} 引用。")
            appendLine("  示例: 参数名 APP_NAME 可写为 ${'$'}{APP_NAME}")
            appendLine()
            appendLine("内置上下文变量:")
            appendLine("  ${'$'}{server.id} - 服务器 ID")
            appendLine("  ${'$'}{server.name} - 服务器名称")
            appendLine("  ${'$'}{server.host} - 服务器主机/IP")
            appendLine("  ${'$'}{server.port} - SSH 端口")
            appendLine("  ${'$'}{server.user} - SSH 用户名")
            appendLine("  ${'$'}{server.address} - user@host:port 展示地址")
            appendLine("  ${'$'}{server.auth} - 认证方式 password/key")
            appendLine("  ${'$'}{mapping.id} - 当前目录映射 ID")
            appendLine("  ${'$'}{mapping.name} - 当前目录映射名称")
            appendLine("  ${'$'}{mapping.localDir} - 映射本地根目录")
            appendLine("  ${'$'}{mapping.remoteDir} - 映射远程根目录")
            appendLine("  ${'$'}{mapping.serverId} - 映射绑定的服务器 ID")
            appendLine("  ${'$'}{path.remoteDir} - 当前解析出的远程目录")
            appendLine("  ${'$'}{path.projectBase} - 当前项目根目录")
            appendLine("  ${'$'}{path.artifact} - 构建产物路径，未提供时为空")
            appendLine("  ${'$'}{path.local} - 当前选择的第一个本地路径")
            appendLine("  ${'$'}{path.locals} - 当前选择的所有本地路径")
            appendLine("  ${'$'}{path.local.0} - 第 1 个本地路径，path.local.1 为第 2 个，以此类推")
            appendLine()
            appendLine("转义: 写 $${'$'}{VAR} 可输出字面量 ${'$'}{VAR}。")
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
        if (nameField.text.isBlank()) return ValidationInfo("名称不能为空", nameField)
        if (commandArea.text.isBlank()) return ValidationInfo("命令模板不能为空", commandArea)
        val timeout = timeoutField.text.trim().toIntOrNull()
        if (timeout == null || timeout <= 0) return ValidationInfo("超时必须是正整数", timeoutField)
        val names = mutableSetOf<String>()
        paramModel.getData().forEach { param ->
            if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(param.name)) {
                return ValidationInfo("参数名只能包含字母、数字和下划线，且不能以数字开头", paramTable)
            }
            if (!names.add(param.name)) return ValidationInfo("参数名重复: ${param.name}", paramTable)
            if (param.type == ScriptParam.ParamType.ENUM && param.options.isEmpty()) {
                return ValidationInfo("枚举参数 ${param.name} 至少需要一个选项", paramTable)
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
            group = groupField.text.trim().ifBlank { "默认" },
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
        private val columns = arrayOf("Name", "Label", "Type", "Required", "Default", "Options")
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
    private val requiredCheck = JBCheckBox("必填")
    private val defaultField = JBTextField()
    private val optionsField = JBTextField()
    private val descriptionField = JBTextArea(3, 30)

    init {
        title = if (existingParam == null) "添加参数" else "编辑参数"
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
                .addLabeledComponent("名称:", nameField)
                .addLabeledComponent("显示名:", labelField)
                .addLabeledComponent("类型:", typeCombo)
                .addComponent(requiredCheck)
                .addLabeledComponent("默认值:", defaultField)
                .addLabeledComponent("枚举选项(逗号分隔):", optionsField)
                .addLabeledComponent("说明:", JBScrollPane(descriptionField))
                .panel
        )
        panel.add(Box.createVerticalStrut(4))
        panel.preferredSize = Dimension(460, 320)
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo("参数名称不能为空", nameField)
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(nameField.text.trim())) {
            return ValidationInfo("参数名只能包含字母、数字和下划线，且不能以数字开头", nameField)
        }
        if (typeCombo.selectedItem == ScriptParam.ParamType.ENUM && optionsField.text.split(",").none { it.trim().isNotBlank() }) {
            return ValidationInfo("枚举参数需要填写选项", optionsField)
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
