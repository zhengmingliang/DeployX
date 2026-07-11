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
    private val fileList: List<String> = emptyList()
) : DialogWrapper(project) {

    init {
        title = DeployXBundle.message("rollback.dialog.title")
        setOKButtonText(DeployXBundle.message("rollback.dialog.okButton"))
        setCancelButtonText(DeployXBundle.message("rollback.dialog.cancelButton"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(10), JBUI.scale(10)))
        panel.border = JBUI.Borders.empty(10)

        // 警告信息
        val warningPanel = createWarningPanel()
        panel.add(warningPanel, BorderLayout.NORTH)

        // 详情信息
        val detailsPanel = createDetailsPanel()
        panel.add(detailsPanel, BorderLayout.CENTER)

        // 文件列表（如果有）
        if (fileList.isNotEmpty()) {
            val filesPanel = createFilesPanel()
            panel.add(filesPanel, BorderLayout.SOUTH)
        }

        return panel
    }

    /**
     * 创建警告信息面板
     */
    private fun createWarningPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        // 使用标准的黄色警告颜色
        val warningColor = Color(0xE6, 0x7C, 0x00)  // 橙色
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(warningColor, 1),
            JBUI.Borders.empty(10)
        )
        panel.background = Color(warningColor.red, warningColor.green, warningColor.blue, 30)

        val warningIcon = JBLabel("⚠️").apply {
            font = font.deriveFont(24f)
            foreground = warningColor
        }

        val warningText = JBLabel("<html>${DeployXBundle.message("rollback.dialog.warningTitle")}<br>" +
                DeployXBundle.message("rollback.dialog.warningMessage") + "</html>").apply {
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

        // 服务器
        addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.server"),
            "${record.serverName} (${record.serverAddress})", gridy++)

        // 目标路径
        addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.targetPath"),
            record.targetPath, gridy++)

        // 备份文件
        addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.backupFile"),
            record.backupFilePath, gridy++)

        // 文件数量
        addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.fileCount"),
            if (fileList.isNotEmpty()) fileList.size.toString() else DeployXBundle.message("rollback.dialog.fileCount.notListed"),
            gridy++)

        // 原始部署时间
        addLabelValueRow(panel, DeployXBundle.message("rollback.dialog.deployTime"),
            record.formattedDate, gridy++)

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
