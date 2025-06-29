package com.alianga.idea.filesync.ui.dialog

import com.alianga.idea.filesync.model.ServerConfig
import com.alianga.idea.filesync.ssh.SshConnection
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import kotlin.concurrent.thread

/**
 * 远程路径选择对话框
 * 使用后台线程加载目录，避免 UI 阻塞
 */
class RemotePathChooserDialog(
    private val server: ServerConfig,
    private val initialPath: String = "/"
) : DialogWrapper(null) {

    companion object {
        private val LOG = Logger.getInstance(RemotePathChooserDialog::class.java)
    }

    private val pathField = JBTextField(initialPath)
    private val directoryList = JBList<String>()
    private val listModel = DefaultListModel<String>()
    private val loadButton = JButton("加载目录")
    private val upButton = JButton("上级目录")

    private var currentPath: String = initialPath
    private var isLoading = false
        set(value) {
            field = value
            updateUIState()
        }

    init {
        title = "选择远程路径 - ${server.name} (${server.displayAddress})"
        directoryList.model = listModel
        setupUI()
        init()
        // 延迟加载，确保对话框 UI 完全初始化
        SwingUtilities.invokeLater {
            loadDirectory(currentPath)
        }
    }

    private fun setupUI() {
        // 设置列表渲染器
        directoryList.cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(
                list: JList<out String>,
                value: String?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value == null) return
                icon = if (value.endsWith("/")) {
                    AllIcons.Nodes.Folder
                } else {
                    AllIcons.FileTypes.Any_type
                }
                if (value.endsWith("/")) {
                    append(value.removeSuffix("/"), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.BLUE))
                } else {
                    append(value, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
                }
            }
        }

        // 双击选择目录
        directoryList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val selected = directoryList.selectedValue
                    if (selected != null && selected.endsWith("/")) {
                        val itemName = selected.removeSuffix("/")
                        val newPath = if (currentPath == "/") {
                            "/$itemName"
                        } else {
                            "$currentPath/$itemName"
                        }
                        pathField.text = newPath
                        loadDirectory(newPath)
                    }
                }
            }
        })

        // 回车选择
        directoryList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    val selected = directoryList.selectedValue
                    if (selected != null && selected.endsWith("/")) {
                        val itemName = selected.removeSuffix("/")
                        val newPath = if (currentPath == "/") {
                            "/$itemName"
                        } else {
                            "$currentPath/$itemName"
                        }
                        pathField.text = newPath
                        loadDirectory(newPath)
                    }
                }
            }
        })

        // 按钮事件
        loadButton.addActionListener {
            loadDirectory(pathField.text.trim())
        }

        upButton.addActionListener {
            goUp()
        }

        // 路径回车直接加载
        pathField.addActionListener {
            loadDirectory(pathField.text.trim())
        }
    }

    private fun updateUIState() {
        loadButton.isEnabled = !isLoading
        upButton.isEnabled = !isLoading
        directoryList.isEnabled = !isLoading
        pathField.isEnabled = !isLoading
        if (isLoading) {
            loadButton.text = "加载中..."
        } else {
            loadButton.text = "加载目录"
        }
    }

    private fun goUp() {
        if (currentPath == "/" || currentPath.isBlank()) {
            return
        }
        val parentPath = currentPath.substringBeforeLast('/')
        val newPath = if (parentPath.isBlank()) "/" else parentPath
        pathField.text = newPath
        loadDirectory(newPath)
    }

    private fun loadDirectory(path: String) {
        if (isLoading) return

        val normalizedPath = if (path.isBlank()) "/" else path.trim().replace("\\", "/")
        currentPath = normalizedPath
        pathField.text = normalizedPath
        isLoading = true
        updateUIState()

        LOG.info("Loading remote directory: $normalizedPath for server ${server.displayAddress}")

        // 使用简单的后台线程加载，避免 IntelliJ Task 框架的复杂性
        thread(start = true, isDaemon = true, name = "RemoteDirLoader") {
            var result: List<String>? = null
            var error: String? = null
            val connection = SshConnection(server)

            try {
                LOG.info("Connecting to ${server.displayAddress}...")
                if (!connection.connect()) {
                    error = "无法连接到服务器"
                    LOG.warn("Failed to connect to ${server.displayAddress}")
                } else {
                    LOG.info("Connected successfully, executing ls command...")

                    // 执行 ls 命令获取目录
                    val lsResult = connection.executeCommand("cd \"$normalizedPath\" && ls -1F")
                    LOG.info("ls command result - success: ${lsResult.success}, exitCode: ${lsResult.exitCode}")
                    LOG.info("ls output: ${lsResult.output}")
                    if (lsResult.error.isNotBlank()) {
                        LOG.warn("ls error: ${lsResult.error}")
                    }

                    if (!lsResult.success) {
                        error = "无法读取目录: ${lsResult.error}"
                    } else {
                        result = lsResult.output.split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && it != "." && it != ".." }
                            .sortedWith(compareBy({ !it.endsWith("/") }, { it }))

                        LOG.info("Found ${result?.size ?: 0} items in directory")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to load remote directory", e)
                error = e.message ?: "加载目录失败"
            } finally {
                connection.disconnect()
            }

            // 在 UI 线程更新结果
            SwingUtilities.invokeLater {
                isLoading = false
                updateUIState()
                listModel.clear()
                if (error != null) {
                    JOptionPane.showMessageDialog(
                        this@RemotePathChooserDialog.contentPanel,
                        "加载目录失败: $error",
                        "错误",
                        JOptionPane.ERROR_MESSAGE
                    )
                } else {
                    result?.forEach { listModel.addElement(it) }
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(loadButton)
            add(Box.createHorizontalStrut(5))
            add(upButton)
        }

        val scrollPane = JBScrollPane(directoryList).apply {
            preferredSize = Dimension(500, 400)
        }

        val hintLabel = JLabel("提示：双击目录进入，按 OK 确认当前路径").apply {
            foreground = JBColor.GRAY
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("远程路径:", pathField)
            .addComponent(buttonPanel)
            .addVerticalGap(8)
            .addComponent(scrollPane)
            .addVerticalGap(8)
            .addComponent(hintLabel)
            .panel
    }

    override fun doValidate(): ValidationInfo? {
        if (pathField.text.isBlank()) {
            return ValidationInfo("路径不能为空", pathField)
        }
        return null
    }

    fun getSelectedPath(): String {
        return pathField.text.trim().let {
            if (it.endsWith("/")) it else "$it/"
        }
    }
}
