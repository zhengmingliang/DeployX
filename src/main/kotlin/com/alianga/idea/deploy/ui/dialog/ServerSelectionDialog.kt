package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel

/**
 * 服务器选择对话框 - 用于右键菜单选择目标服务器
 */
class ServerSelectionDialog(
    private val servers: List<ServerConfig>,
    private val titleText: String = DeployXBundle.message("dialog.server.select.title"),
    private val messageText: String = DeployXBundle.message("dialog.server.select.message"),
    private val showCommandOptions: Boolean = false,
    private val commandAvailabilityByServerId: Map<String, CommandAvailability> = emptyMap()
) : DialogWrapper(null) {

    data class CommandAvailability(
        val hasPreCommand: Boolean = false,
        val hasPostCommand: Boolean = false
    )

    private val serverList = JBList(servers.map { "${it.id} - ${it.name} (${it.displayAddress})" })
    private val executePreCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePreCommand"), false)
    private val executePostCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePostCommand"), false)

    var selectedServer: ServerConfig? = null
        private set

    var executePreCommand: Boolean = false
        private set

    var executePostCommand: Boolean = false
        private set

    init {
        title = titleText
        serverList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        if (servers.isNotEmpty()) {
            serverList.selectedIndex = 0
        }
        serverList.addListSelectionListener {
            if (!it.valueIsAdjusting) updateCommandOptions()
        }
        init()
        updateCommandOptions()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JScrollPane(serverList)
        scrollPane.preferredSize = Dimension(400, 200)

        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(messageText))
            .addVerticalGap(4)
            .addComponent(scrollPane)

        if (showCommandOptions) {
            builder
                .addVerticalGap(8)
                .addComponent(executePreCommandCheck)
                .addComponent(executePostCommandCheck)
        }

        val panel = builder.panel
        panel.preferredSize = Dimension(460, if (showCommandOptions) 340 else 280)
        return panel
    }

    override fun doOKAction() {
        val selectedIndex = serverList.selectedIndex
        if (selectedIndex >= 0 && selectedIndex < servers.size) {
            selectedServer = servers[selectedIndex]
        }
        executePreCommand = showCommandOptions && executePreCommandCheck.isEnabled && executePreCommandCheck.isSelected
        executePostCommand = showCommandOptions && executePostCommandCheck.isEnabled && executePostCommandCheck.isSelected
        super.doOKAction()
    }

    private fun updateCommandOptions() {
        if (!showCommandOptions) return
        val selectedIndex = serverList.selectedIndex
        val server = servers.getOrNull(selectedIndex)
        val availability = server?.let { commandAvailabilityByServerId[it.id] } ?: CommandAvailability()

        executePreCommandCheck.isEnabled = availability.hasPreCommand
        executePostCommandCheck.isEnabled = availability.hasPostCommand
        if (!availability.hasPreCommand) executePreCommandCheck.isSelected = false
        if (!availability.hasPostCommand) executePostCommandCheck.isSelected = false
    }
}
