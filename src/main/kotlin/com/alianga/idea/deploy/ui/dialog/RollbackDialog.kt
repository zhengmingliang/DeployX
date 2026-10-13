package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.HistoryRecord
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

/**
 * 回滚确认对话框
 * 显示回滚操作的详细信息，让用户确认后再执行
 */
class RollbackDialog(
    private val project: Project,
    private val record: HistoryRecord,
    private val fileList: List<String> = emptyList(),
    /** 1.1.1：此后同一目标上成功的部署 / 推送次数（警告用） */
    private val newerCount: Int = 0
) : DialogWrapper(project) {

    // 1.1.1：两个选项 + 命令原文预览（原型 / 实施方案 3.7）
    private val backupFirstCheck = com.intellij.ui.components.JBCheckBox(DeployXBundle.message("rollback.dialog.backupFirst"), true)
    private val runPostCheck = com.intellij.ui.components.JBCheckBox(DeployXBundle.message("rollback.dialog.runPost"), false)
    private val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
    private val commandArea = JBTextArea().apply {
        isEditable = false; lineWrap = true
        font = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.let { JBUI.Fonts.create(it.editorFontName, it.editorFontSize) }
    }

    /** 用户确认后的选项 */
    val options: com.alianga.idea.deploy.service.RollbackCommands.Options
        get() = com.alianga.idea.deploy.service.RollbackCommands.Options(
            backupFirst = backupFirstCheck.isSelected,
            runPostCommand = runPostCheck.isVisible && runPostCheck.isSelected
        )

    private fun refreshCommandPreview() {
        commandArea.text = com.alianga.idea.deploy.service.RollbackCommands.preview(record, options, timestamp).joinToString("\n")
    }

    init {
        title = DeployXBundle.message("rollback.v2.title")
        setOKButtonText(DeployXBundle.message("rollback.v2.ok"))
        setCancelButtonText(DeployXBundle.message("rollback.dialog.cancelButton"))
        init()
    }

    /**
     * 1.1.0：破坏性操作让安全选项成为默认——「取消」是默认按钮（主色、Enter 触发），
     * 「回滚」为红色描边的次要按钮。
     */
    override fun createActions(): Array<javax.swing.Action> {
        okAction.putValue(DEFAULT_ACTION, null)
        okAction.putValue("JComponent.outline", "error")
        // 1.1.1：危险按钮带回滚图标
        okAction.putValue(javax.swing.Action.SMALL_ICON, com.intellij.icons.AllIcons.Actions.Rollback)
        cancelAction.putValue(DEFAULT_ACTION, true)
        return arrayOf(cancelAction, okAction)
    }

    override fun getPreferredFocusedComponent(): JComponent? = getButton(cancelAction)

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(10), JBUI.scale(10)))
        panel.border = JBUI.Borders.empty(10)

        // 1.1.1：副标题 + 警告信息
        val subtitle = JBLabel(DeployXBundle.message("rollback.v2.subtitle",
            record.serverName.ifBlank { record.serverId })).apply { border = JBUI.Borders.emptyBottom(6) }
        panel.add(JPanel(BorderLayout()).apply {
            add(subtitle, BorderLayout.NORTH)
            add(createWarningPanel(), BorderLayout.CENTER)
        }, BorderLayout.NORTH)

        // 详情信息
        val detailsPanel = createDetailsPanel()
        panel.add(detailsPanel, BorderLayout.CENTER)

        // 1.1.1：将在服务器上执行的命令原文 + 两个选项 + 文件列表
        runPostCheck.isVisible = record.postCommand.isNotBlank()
        backupFirstCheck.addItemListener { refreshCommandPreview() }
        runPostCheck.addItemListener { refreshCommandPreview() }
        refreshCommandPreview()
        val south = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(com.intellij.ui.TitledSeparator(DeployXBundle.message("rollback.dialog.commandsTitle")))
            add(JBScrollPane(commandArea).apply { preferredSize = JBUI.size(520, 70) })
            add(backupFirstCheck)
            add(runPostCheck)
            if (fileList.isNotEmpty()) add(createFilesPanel())
            components.forEach { (it as? JComponent)?.alignmentX = java.awt.Component.LEFT_ALIGNMENT }
        }
        panel.add(south, BorderLayout.SOUTH)

        return panel
    }

    /**
     * 创建警告信息面板
     */
    private fun createWarningPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        // 使用标准的黄色警告颜色
        // 1.1.0：警告色走 DeployXColors（JBColor，随主题切换），不再写死 Color
        val warningColor = com.alianga.idea.deploy.ui.DeployXColors.warning
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(warningColor, 1),
            JBUI.Borders.empty(10)
        )
        panel.background = Color(warningColor.red, warningColor.green, warningColor.blue, 30)

        val warningIcon = JBLabel(com.intellij.icons.AllIcons.General.WarningDialog)

        // 1.1.1：警告补全——同名文件被覆盖、新增文件不删除、此后 N 次部署结果也会被覆盖
        val newer = if (newerCount > 0) "<br>" + DeployXBundle.message("rollback.dialog.warningNewer", newerCount) else ""
        val warningText = JBLabel("<html>${DeployXBundle.message("rollback.dialog.warningTitle")}<br>" +
                DeployXBundle.message("rollback.dialog.warningMessage") + "<br>" +
                DeployXBundle.message("rollback.dialog.warningOverwrite") + newer + "</html>").apply {
            foreground = warningColor
        }

        val contentPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }

        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            insets = Insets(0, 0, 0, JBUI.scale(10))
            anchor = GridBagConstraints.NORTH
        }
        contentPanel.add(warningIcon, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
        }
        contentPanel.add(warningText, gbc)

        panel.add(contentPanel, BorderLayout.CENTER)
        return panel
    }

    /**
     * 创建详情信息面板
     */
    private fun createDetailsPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        var gridy = 0

        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(JBUI.scale(5), JBUI.scale(5), JBUI.scale(5), JBUI.scale(5))
        }

        // 1.1.1：字段为 服务器 / 部署时间 / 备份文件 / 还原到；不再显示「待确认」的文件数
        addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.server"),
            "${record.serverName} (${record.serverAddress})", gridy++)
        addLabelValueRow(panel, DeployXBundle.message("rollback.v2.deployTime"),
            record.formattedDate, gridy++)
        addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.backupFile"),
            record.backupFilePath, gridy++)
        addLabelValueRow(panel, DeployXBundle.message("rollback.v2.restoreTo"),
            record.targetPath, gridy++)
        if (fileList.isNotEmpty()) {
            addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.fileCount"), fileList.size.toString(), gridy++)
        }

        return panel
    }

    /**
     * 添加标签-值行
     */
    private fun addLabelValueRow(panel: JPanel, label: String, value: String, gridy: Int) {
        val gbc = GridBagConstraints().apply {
            this.gridy = gridy
            insets = Insets(JBUI.scale(5), JBUI.scale(5), JBUI.scale(5), JBUI.scale(5))
        }

        // 标签
        gbc.gridx = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.NONE
        panel.add(JBLabel("$label:"), gbc)

        // 值
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(JBLabel(value), gbc)
    }

    /**
     * 创建文件列表面板
     */
    private fun createFilesPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(5), JBUI.scale(5)))

        val titleLabel = JBLabel(DeployXBundle.message("rollback.dialog.filesTitle")).apply {
            border = JBUI.Borders.empty(5, 0)
        }
        panel.add(titleLabel, BorderLayout.NORTH)

        val textArea = JBTextArea().apply {
            isEditable = false
            rows = 8
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12)
        }

        // 显示文件（最多显示 50 个，超过的显示省略号）
        val displayFiles = fileList.take(50)
        textArea.text = displayFiles.joinToString("\n")
        if (fileList.size > 50) {
            textArea.text += "\n\n... " + DeployXBundle.message("rollback.dialog.moreFiles", fileList.size - 50)
        }

        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun createSouthPanel(): JComponent {
        val southPanel = super.createSouthPanel()

        // 添加确认复选框（可选，用于二次确认）
        // 注意：为了简化用户体验，这里不添加额外的复选框
        // 用户通过点击"确认回滚"按钮来表示已经理解风险

        return southPanel
    }
}
