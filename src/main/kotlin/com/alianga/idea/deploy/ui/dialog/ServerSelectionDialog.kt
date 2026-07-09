package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 服务器选择对话框 - 用于右键菜单选择目标服务器
 *
 * 特性：
 * - 支持实时搜索过滤（按 id / 名称 / 主机 / 用户名 匹配）
 * - 适配较多服务器场景，列表尺寸加大
 * - 对话框打开后，键盘输入字母/数字会自动聚焦到搜索框并输入（无需先点击搜索框）
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

    private val listModel = CollectionListModel(servers.map { formatServer(it) })
    private val serverList = JBList(listModel)
    private val searchField = JBTextField()
    private val executePreCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePreCommand"), false)
    private val executePostCommandCheck = JBCheckBox(DeployXBundle.message("dialog.server.select.executePostCommand"), false)

    /** 当前过滤后展示的服务器列表（搜索框为空时等于 servers） */
    private var filteredServers: List<ServerConfig> = servers

    /** 键盘事件分发器：对话框可见时，按字母/数字键自动把焦点路由到搜索框 */
    private var keyDispatcher: KeyEventDispatcher? = null

    var selectedServer: ServerConfig? = null
        private set

    /** 多选模式：选中的服务器列表（单选时只有一个元素） */
    var selectedServers: List<ServerConfig> = emptyList()
        private set

    var executePreCommand: Boolean = false
        private set

    var executePostCommand: Boolean = false
        private set

    init {
        title = titleText
        serverList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        if (servers.isNotEmpty()) {
            serverList.selectedIndex = 0
        }
        serverList.addListSelectionListener {
            if (!it.valueIsAdjusting) updateCommandOptions()
        }
        // 双击列表项直接确认选择（与 RemotePathChooserDialog 行为一致）
        serverList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && filteredServers.isNotEmpty()) {
                    doOKAction()
                }
            }
        })

        // 搜索框：占位提示文本
        searchField.emptyText.text = DeployXBundle.message("dialog.server.select.search.placeholder")
        // 实时过滤
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterList()
            override fun removeUpdate(e: DocumentEvent) = filterList()
            override fun changedUpdate(e: DocumentEvent) = filterList()
        })
        // 在搜索框按 Enter：若仅一个匹配则直接确认，否则把焦点交给列表
        searchField.addActionListener {
            if (filteredServers.size == 1) {
                serverList.selectedIndex = 0
                doOKAction()
            } else if (filteredServers.isNotEmpty()) {
                serverList.requestFocusInWindow()
            }
        }

        init()
        updateCommandOptions()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JScrollPane(serverList)
        // 调大列表尺寸，尽量显示全服务器列表
        scrollPane.preferredSize = Dimension(520, 320)
        scrollPane.minimumSize = Dimension(420, 220)

        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(messageText))
            .addVerticalGap(6)
            .addComponent(searchField)
            .addVerticalGap(4)
            .addComponent(scrollPane)

        if (showCommandOptions) {
            builder
                .addVerticalGap(8)
                .addComponent(executePreCommandCheck)
                .addComponent(executePostCommandCheck)
        }

        val panel = builder.panel
        // 调大对话框整体尺寸以容纳更多服务器
        panel.preferredSize = Dimension(580, if (showCommandOptions) 500 else 440)

        // 注册键盘事件路由：对话框可见时，按字母/数字键自动把焦点转到搜索框
        registerKeyRouting()

        // 默认让搜索框获得焦点，方便直接输入
        SwingUtilities.invokeLater { searchField.requestFocusInWindow() }

        return panel
    }

    /**
     * 注册全局键盘事件分发器：对话框可见时，若焦点不在任何文本组件上，
     * 按字母/数字键会自动把焦点路由到搜索框并输入字符（无需先点击搜索框）。
     */
    private fun registerKeyRouting() {
        // 先移除可能残留的 dispatcher（避免重复注册）
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
        }
        keyDispatcher = KeyEventDispatcher { e ->
            // 仅处理 KEY_TYPED（产生可输入字符的事件），且对话框必须可见
            if (e.id != KeyEvent.KEY_TYPED || !isShowing) {
                return@KeyEventDispatcher false
            }
            // 焦点已经在搜索框或任意文本组件时，让该组件正常处理（不拦截）
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner === searchField || focusOwner is JTextComponent) {
                return@KeyEventDispatcher false
            }
            val c = e.keyChar
            // 仅路由字母数字和常见的 id/主机连接符，避免拦截 Enter、Esc、Tab、方向键等控制键
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                searchField.requestFocusInWindow()
                // 焦点转移是异步的，同步把字符追加到搜索框，避免字符丢失
                searchField.text += c.toString()
                searchField.caretPosition = searchField.text.length
                // 消费事件，阻止默认分发
                return@KeyEventDispatcher true
            }
            false
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
    }

    override fun dispose() {
        // 移除键盘事件分发器，避免内存泄漏
        keyDispatcher?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it)
            keyDispatcher = null
        }
        super.dispose()
    }

    override fun doOKAction() {
        // 多选模式：收集所有选中的服务器
        val selectedIndices = serverList.selectedIndices
        selectedServers = selectedIndices.filter { it >= 0 && it < filteredServers.size }
            .map { filteredServers[it] }
        // 向后兼容：selectedServer 取第一个
        selectedServer = selectedServers.firstOrNull()
        executePreCommand = showCommandOptions && executePreCommandCheck.isEnabled && executePreCommandCheck.isSelected
        executePostCommand = showCommandOptions && executePostCommandCheck.isEnabled && executePostCommandCheck.isSelected
        super.doOKAction()
    }

    private fun updateCommandOptions() {
        if (!showCommandOptions) return
        val selectedIndex = serverList.selectedIndex
        val server = filteredServers.getOrNull(selectedIndex)
        val availability = server?.let { commandAvailabilityByServerId[it.id] } ?: CommandAvailability()

        executePreCommandCheck.isEnabled = availability.hasPreCommand
        executePostCommandCheck.isEnabled = availability.hasPostCommand
        if (!availability.hasPreCommand) executePreCommandCheck.isSelected = false
        if (!availability.hasPostCommand) executePostCommandCheck.isSelected = false
    }

    /**
     * 根据搜索框关键字过滤服务器列表（不区分大小写）
     * 匹配维度：id / 名称 / 主机 / 用户名
     */
    private fun filterList() {
        val keyword = searchField.text.trim().lowercase()

        // 记住过滤前选中服务器的 id，便于过滤后恢复选择
        val previousSelectedId = filteredServers.getOrNull(serverList.selectedIndex)?.id

        val matched = if (keyword.isEmpty()) {
            servers
        } else {
            servers.filter {
                it.id.lowercase().contains(keyword) ||
                    it.name.lowercase().contains(keyword) ||
                    it.host.lowercase().contains(keyword) ||
                    it.user.lowercase().contains(keyword)
            }
        }

        filteredServers = matched
        listModel.replaceAll(matched.map { formatServer(it) })

        // 恢复选择：优先选回之前选中的服务器；否则选第一项
        val restoreIndex = previousSelectedId?.let { id ->
            matched.indexOfFirst { it.id == id }
        } ?: -1
        serverList.selectedIndex = if (restoreIndex >= 0) restoreIndex else 0

        updateCommandOptions()
    }

    private fun formatServer(server: ServerConfig): String =
        "${server.id} - ${server.name} (${server.displayAddress})"
}
