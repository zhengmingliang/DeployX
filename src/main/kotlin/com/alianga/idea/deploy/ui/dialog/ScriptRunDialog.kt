package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptParam
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.model.ScriptRunResult
import com.alianga.idea.deploy.service.ScriptManager
import com.alianga.idea.deploy.service.ServerManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.AbstractAction

/**
 * 脚本运行对话框。
 */
class ScriptRunDialog(
    private val project: Project,
    private val script: ScriptConfig,
    private val context: ScriptRunContext = ScriptRunContext.EMPTY,
    private val logCallback: ((String) -> Unit)? = null
) : DialogWrapper(project) {

    private val scriptManager = ScriptManager.getInstance()
    private val serverCombo = JComboBox<String>()
    private val paramComponents = linkedMapOf<String, JComponent>()
    private val previewArea = JBTextArea(8, 60)
    private var runResult: ScriptRunResult? = null

    init {
        title = "运行脚本 - ${script.name}"
        setupServerCombo()
        previewArea.isEditable = false
        previewArea.font = Font("Monospaced", Font.PLAIN, 12)
        init()
        setOKButtonText("运行")
    }

    private fun setupServerCombo() {
        ServerManager.getInstance().getServers().forEach { server ->
            serverCombo.addItem("${server.id} - ${server.name} (${server.displayAddress})")
        }
        serverCombo.preferredSize = Dimension(560, serverCombo.preferredSize.height)
        serverCombo.minimumSize = Dimension(420, serverCombo.minimumSize.height)
        val preferred = script.serverId.ifBlank { context.server?.id ?: context.mapping?.serverId ?: "" }
        val servers = ServerManager.getInstance().getServers()
        val index = servers.indexOfFirst { it.id == preferred }
        if (index >= 0) serverCombo.selectedIndex = index
    }

    override fun createActions() = arrayOf(object : AbstractAction("Dry Run") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            try {
                renderPreview()
            } catch (ex: Exception) {
                Messages.showErrorDialog(ex.message ?: "命令渲染失败", "Dry Run")
            }
        }
    }, okAction, cancelAction)

    override fun createCenterPanel(): JComponent {
        val paramsPanel = FormBuilder.createFormBuilder()
        script.params.forEach { param ->
            val comp = createParamComponent(param)
            paramComponents[param.name] = comp
            paramsPanel.addLabeledComponent(param.displayLabel + if (param.required) " *:" else ":", comp)
        }

        val variablesArea = JBTextArea(buildVariablesHelpText(), 9, 60).apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
        }
        val top = FormBuilder.createFormBuilder()
            .addComponent(JLabel(script.description.ifBlank { "脚本命令执行前会先渲染参数和上下文变量。" }))
            .addLabeledComponent("目标服务器:", serverCombo)
            .addVerticalGap(6)
            .addComponent(paramsPanel.panel)
            .addVerticalGap(6)
            .addLabeledComponent("变量说明:", JBScrollPane(variablesArea).apply { preferredSize = Dimension(680, 170) })
            .addVerticalGap(6)
            .addLabeledComponent("命令预览:", JBScrollPane(previewArea))
            .panel

        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.CENTER)
            preferredSize = Dimension(820, 680)
        }
    }

    private fun buildVariablesHelpText(): String {
        val paramHelp = if (script.params.isEmpty()) {
            "脚本参数: 无"
        } else {
            buildString {
                appendLine("脚本参数变量:")
                script.params.forEach { param ->
                    val required = if (param.required) "必填" else "可选"
                    val defaultValue = if (param.defaultValue.isNotBlank()) "，默认值: ${param.defaultValue}" else ""
                    val options = if (param.options.isNotEmpty()) "，选项: ${param.options.joinToString(", ")}" else ""
                    val desc = param.description.ifBlank { "无说明" }
                    appendLine("  ${'$'}{${param.name}} - ${param.displayLabel}，${param.type.name}，$required$defaultValue$options。$desc")
                }
            }.trimEnd()
        }

        return buildString {
            appendLine(paramHelp)
            appendLine()
            appendLine("上下文变量:")
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
            appendLine("提示: 变量会在执行前替换。参数值和上下文值会自动进行 shell quote。")
        }.trimEnd()
    }

    private fun createParamComponent(param: ScriptParam): JComponent {
        return when (param.type) {
            ScriptParam.ParamType.BOOLEAN -> JBCheckBox(param.description).apply {
                isSelected = param.defaultValue.equals("true", true) || param.defaultValue == "1" || param.defaultValue.equals("yes", true)
            }
            ScriptParam.ParamType.ENUM -> JComboBox(param.options.toTypedArray()).apply {
                if (param.defaultValue.isNotBlank()) selectedItem = param.defaultValue
            }
            else -> JBTextField(param.defaultValue).apply { emptyText.text = param.description }
        }
    }

    override fun doValidate(): ValidationInfo? {
        return try {
            renderPreview()
            null
        } catch (e: Exception) {
            ValidationInfo(e.message ?: "脚本参数错误")
        }
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

    private fun selectedServerId(): String? = serverCombo.selectedItem?.toString()?.substringBefore(" - ")?.trim()?.takeIf { it.isNotBlank() }

    private fun effectiveContext(): ScriptRunContext {
        val server = selectedServerId()?.let { ServerManager.getInstance().getServer(it) } ?: context.server
        return context.copy(server = server)
    }

    private fun renderPreview(): String {
        val rendered = scriptManager.renderCommand(script, collectParams(), effectiveContext())
        previewArea.text = rendered
        return rendered
    }

    override fun doOKAction() {
        val command = try {
            renderPreview()
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: "命令渲染失败", "运行脚本")
            return
        }

        val serverId = selectedServerId()
        if (serverId.isNullOrBlank()) {
            Messages.showWarningDialog("请选择目标服务器", "运行脚本")
            return
        }

        if (script.confirmBeforeRun || scriptManager.hasDangerousCommand(script, command)) {
            val result = Messages.showYesNoDialog(
                "即将在远程服务器执行以下命令：\n\n$command\n\n确认继续？",
                "确认执行脚本",
                "执行",
                "取消",
                Messages.getWarningIcon()
            )
            if (result != Messages.YES) return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Running script ${script.name}...", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = scriptManager.runScript(
                    script = script,
                    rawParams = collectParams(),
                    context = effectiveContext(),
                    serverId = serverId,
                    logCallback = logCallback,
                    confirmCallback = { true }
                )
                runResult = result
                SwingUtilities.invokeLater {
                    if (!result.success) {
                        Messages.showWarningDialog(result.error.ifBlank { "脚本执行失败" }, "运行脚本")
                    }
                    close(OK_EXIT_CODE)
                }
            }
        })
    }

    fun getRunResult(): ScriptRunResult? = runResult
}
