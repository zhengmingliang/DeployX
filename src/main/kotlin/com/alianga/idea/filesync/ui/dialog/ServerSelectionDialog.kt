package com.alianga.idea.filesync.ui.dialog

import com.alianga.idea.filesync.model.ServerConfig
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.JScrollPane
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * 服务器选择对话框 - 用于右键菜单选择目标服务器
 */
class ServerSelectionDialog(
    private val servers: List<ServerConfig>,
    private val titleText: String = "选择目标服务器",
    private val messageText: String = "请选择要上传到的服务器："
) : DialogWrapper(null) {

    private val serverList = JBList(servers.map { "${it.id} - ${it.name} (${it.displayAddress})" })

    var selectedServer: ServerConfig? = null
        private set

    init {
        title = titleText
        serverList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        if (servers.isNotEmpty()) {
            serverList.selectedIndex = 0
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JScrollPane(serverList)
        scrollPane.preferredSize = Dimension(400, 200)

        val panel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(messageText))
            .addVerticalGap(4)
            .addComponent(scrollPane)
            .panel
        panel.preferredSize = Dimension(450, 280)
        return panel
    }

    override fun doOKAction() {
        val selectedIndex = serverList.selectedIndex
        if (selectedIndex >= 0 && selectedIndex < servers.size) {
            selectedServer = servers[selectedIndex]
        }
        super.doOKAction()
    }
}
