package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptParam
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.service.ScriptManager
import com.alianga.idea.deploy.service.ServerManager
import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * 从脚本库选择脚本并输出插入内容或引用标记。
 */
class ScriptPickerDialog(
    private val project: Project?,
    private val context: ScriptRunContext = ScriptRunContext.EMPTY,
    private val mapping: MappingConfig? = context.mapping,
    private val remoteDir: String? = context.remoteDir,
    private val initialScriptId: String? = null
) : DialogWrapper(project) {

    private val scriptManager = ScriptManager.getInstance()
    private val listModel = DefaultListModel<ScriptConfig>()
    private val scriptList = JBList(listModel)
    private val searchField = JBTextField()
    private val paramsPanel = JPanel(BorderLayout())
    private val paramComponents = linkedMapOf<String, JComponent>()
    private val previewArea = JBTextArea(10, 50)
    private val insertRadio = JBRadioButton("插入脚本内容", true)
    private val referenceRadio = JBRadioButton("引用脚本")
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private var resultCommand: String = ""

    init {
        title = "从脚本库选择"
        previewArea.isEditable = false
        previewArea.font = Font("Monospaced", Font.PLAIN, 12)
        previewArea.lineWrap = true
        previewArea.wrapStyleWord = false
        ButtonGroup().apply {
            add(insertRadio)
            add(referenceRadio)
        }
        insertRadio.addActionListener { updatePreview() }
        referenceRadio.addActionListener { updatePreview() }
        scriptList.cellRenderer = ScriptCellRenderer()
        scriptList.fixedCellHeight = 72
        refreshList(initialScriptId)
        scriptList.addListSelectionListener { if (!it.valueIsAdjusting) showSelectedScript() }
        searchField.emptyText.text = "搜索脚本"
        searchField.addActionListener { refreshList(scriptList.selectedValue?.id ?: initialScriptId) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout(4, 4)).apply {
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(scriptList), BorderLayout.CENTER)
            preferredSize = Dimension(280, 520)
        }
        val modePanel = JPanel().apply {
            add(insertRadio)
            add(referenceRadio)
        }
        val right = JPanel(BorderLayout(4, 4)).apply {
            add(paramsPanel, BorderLayout.NORTH)
            add(JBScrollPane(previewArea), BorderLayout.CENTER)
            add(modePanel, BorderLayout.SOUTH)
        }
        return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            preferredSize = Dimension(900, 580)
            dividerLocation = 300
            resizeWeight = 0.3
        }
    }

    private fun refreshList(preferredScriptId: String? = null) {
        val selectedId = preferredScriptId ?: scriptList.selectedValue?.id
        listModel.clear()
        scriptManager.searchScripts(searchField.text.trim()).forEach { listModel.addElement(it) }
        val idx = (0 until listModel.size()).firstOrNull { listModel.getElementAt(it).id == selectedId } ?: 0
        if (listModel.size() > 0) scriptList.selectedIndex = idx.coerceAtMost(listModel.size() - 1)
    }

    private fun showSelectedScript() {
        val script = scriptList.selectedValue ?: return
        paramComponents.clear()
        val builder = FormBuilder.createFormBuilder()
        script.params.forEach { param ->
            val comp = createParamComponent(param)
            paramComponents[param.name] = comp
            builder.addLabeledComponent(param.displayLabel + if (param.required) " *:" else ":", comp)
        }
        paramsPanel.removeAll()
        paramsPanel.add(builder.panel, BorderLayout.CENTER)
        paramsPanel.revalidate()
        paramsPanel.repaint()
        updatePreview()
    }

    private fun createParamComponent(param: ScriptParam): JComponent {
        val comp: JComponent = when (param.type) {
            ScriptParam.ParamType.BOOLEAN -> JBCheckBox(param.description).apply {
                isSelected = param.defaultValue.equals("true", true) || param.defaultValue == "1" || param.defaultValue.equals("yes", true)
            }
            ScriptParam.ParamType.ENUM -> JComboBox(param.options.toTypedArray()).apply {
                if (param.defaultValue.isNotBlank()) selectedItem = param.defaultValue
            }
            else -> JBTextField(param.defaultValue).apply { emptyText.text = param.description }
        }
        when (comp) {
            is JBTextField -> comp.document.addDocumentListener(SimpleDocumentListener { updatePreview() })
            is JBCheckBox -> comp.addChangeListener { updatePreview() }
            is JComboBox<*> -> comp.addActionListener { updatePreview() }
        }
        return comp
    }

    private fun collectParams(): Map<String, String> {
        return paramComponents.mapValues { (_, comp) ->
            when (comp) {
                is JBTextField -> comp.text.trim()
                is JBCheckBox -> comp.isSelected.toString()
                is JComboBox<*> -> comp.selectedItem?.toString().orEmpty()
                else -> ""
            }
        }
    }

    private fun buildContext(script: ScriptConfig): ScriptRunContext {
        val server = script.serverId.takeIf { it.isNotBlank() }?.let { ServerManager.getInstance().getServer(it) }
            ?: context.server
            ?: mapping?.serverId?.let { ServerManager.getInstance().getServer(it) }
        return context.copy(
            server = server,
            mapping = mapping ?: context.mapping,
            remoteDir = remoteDir ?: context.remoteDir
        )
    }

    private fun updatePreview() {
        val script = scriptList.selectedValue ?: return
        previewArea.text = try {
            if (referenceRadio.isSelected) buildReference(script) else scriptManager.renderCommand(script, collectParams(), buildContext(script))
        } catch (e: Exception) {
            "[ERROR] ${e.message}"
        }
    }

    private fun buildReference(script: ScriptConfig): String {
        val payload = mapOf(
            "scriptId" to script.id,
            "scriptName" to script.name,
            "params" to collectParams()
        )
        return "# DeployX ScriptRef: ${gson.toJson(payload)}"
    }

    override fun doOKAction() {
        val script = scriptList.selectedValue
        if (script == null) {
            Messages.showWarningDialog("请选择脚本", "脚本库")
            return
        }
        resultCommand = try {
            if (referenceRadio.isSelected) buildReference(script) else scriptManager.renderCommand(script, collectParams(), buildContext(script))
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: "命令渲染失败", "脚本库")
            return
        }
        super.doOKAction()
    }

    fun getResultCommand(): String = resultCommand
    fun getScript(): ScriptConfig? = scriptList.selectedValue
    fun isReferenceMode(): Boolean = referenceRadio.isSelected

    private class ScriptCellRenderer : ColoredListCellRenderer<ScriptConfig>() {
        override fun customizeCellRenderer(
            list: javax.swing.JList<out ScriptConfig>,
            value: ScriptConfig?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            if (value == null) return
            append(value.name.ifBlank { "未命名脚本" }, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("    ${value.group.ifBlank { "默认" }}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("\n${summary(value)}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("\n${meta(value)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }

        private fun summary(script: ScriptConfig): String {
            val text = script.description.ifBlank {
                script.command.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            }.ifBlank { "无描述" }
            return text.take(90)
        }

        private fun meta(script: ScriptConfig): String {
            val server = script.serverId.ifBlank { "运行时服务器" }
            val tags = if (script.tags.isEmpty()) "无标签" else script.tags.joinToString(", ")
            return "$server · ${script.params.size} 参数 · $tags · 运行 ${script.runCount} 次"
        }
    }
}

private class SimpleDocumentListener(private val onChange: () -> Unit) : javax.swing.event.DocumentListener {
    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
}
