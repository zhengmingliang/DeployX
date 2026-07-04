package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
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
        title = DeployXBundle.message("dialog.script.run.title", script.name)
        setupServerCombo()
        previewArea.isEditable = false
        previewArea.font = Font("Monospaced", Font.PLAIN, 12)
        init()
        setOKButtonText(DeployXBundle.message("dialog.script.run.button.run"))
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

    override fun createActions() = arrayOf(object : AbstractAction(DeployXBundle.message("dialog.script.run.button.dryRun")) {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            try {
                renderPreview()
            } catch (ex: Exception) {
                Messages.showErrorDialog(ex.message ?: DeployXBundle.message("dialog.script.run.dryrun.failed"), DeployXBundle.message("dialog.script.run.button.dryRun"))
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
            .addComponent(JLabel(script.description.ifBlank { DeployXBundle.message("dialog.script.run.description.default") }))
            .addLabeledComponent(DeployXBundle.message("dialog.script.run.label.targetServer"), serverCombo)
            .addVerticalGap(6)
            .addComponent(paramsPanel.panel)
            .addVerticalGap(6)
            .addLabeledComponent(DeployXBundle.message("dialog.script.run.label.variablesHelp"), JBScrollPane(variablesArea).apply { preferredSize = Dimension(680, 170) })
            .addVerticalGap(6)
            .addLabeledComponent(DeployXBundle.message("dialog.script.run.label.commandPreview"), JBScrollPane(previewArea))
            .panel

        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.CENTER)
            preferredSize = Dimension(820, 680)
        }
    }

    private fun buildVariablesHelpText(): String {
        val paramHelp = if (script.params.isEmpty()) {
            DeployXBundle.message("dialog.script.variables.paramNone")
        } else {
            buildString {
                appendLine(DeployXBundle.message("dialog.script.variables.paramHeader"))
                script.params.forEach { param ->
                    val required = if (param.required) DeployXBundle.message("dialog.script.variables.paramRequired") else DeployXBundle.message("dialog.script.variables.paramOptional")
                    val defaultValue = if (param.defaultValue.isNotBlank()) DeployXBundle.message("dialog.script.variables.paramDefault", param.defaultValue) else ""
                    val options = if (param.options.isNotEmpty()) DeployXBundle.message("dialog.script.variables.paramOptions", param.options.joinToString(", ")) else ""
                    val desc = param.description.ifBlank { DeployXBundle.message("dialog.script.variables.paramNoDesc") }
                    appendLine(DeployXBundle.message("dialog.script.variables.paramLine", param.name, param.displayLabel, param.type.name, required, defaultValue, options, desc))
                }
            }.trimEnd()
        }

        return buildString {
            appendLine(paramHelp)
            appendLine()
            appendLine(DeployXBundle.message("dialog.script.variables.contextHeader"))
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
            ValidationInfo(e.message ?: DeployXBundle.message("dialog.script.run.paramError"))
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
            Messages.showErrorDialog(e.message ?: DeployXBundle.message("dialog.script.run.dryrun.failed"), DeployXBundle.message("dialog.script.run.button.run"))
            return
        }

        val serverId = selectedServerId()
        if (serverId.isNullOrBlank()) {
            Messages.showWarningDialog(DeployXBundle.message("dialog.script.run.selectServer"), DeployXBundle.message("dialog.script.run.button.run"))
            return
        }

        if (script.confirmBeforeRun || scriptManager.hasDangerousCommand(script, command)) {
            val result = Messages.showYesNoDialog(
                DeployXBundle.message("dialog.script.run.confirm.message", command),
                DeployXBundle.message("dialog.script.run.confirm.title"),
                DeployXBundle.message("dialog.script.run.confirm.yes"),
                DeployXBundle.message("common.cancel"),
                Messages.getWarningIcon()
            )
            if (result != Messages.YES) return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, DeployXBundle.message("dialog.script.run.progress.title", script.name), true) {
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
                        Messages.showWarningDialog(result.error.ifBlank { DeployXBundle.message("dialog.script.run.failed") }, DeployXBundle.message("dialog.script.run.button.run"))
                    }
                    close(OK_EXIT_CODE)
                }
            }
        })
    }

    fun getRunResult(): ScriptRunResult? = runResult
}
