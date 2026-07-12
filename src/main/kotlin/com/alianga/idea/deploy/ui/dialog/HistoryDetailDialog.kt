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
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 历史记录详情对话框。
 *
 * 替代原先常驻右侧的详情面板：双击历史记录或点击「查看详情」按钮时弹出，
 * 展示该记录的关键信息（时间/操作/状态/服务器/目录/文件数/备份等）与更新文件清单，
 * 关闭即消失，不占用常驻空间。
 *
 * 更新文件清单优先展示 [HistoryRecord.transferredFiles]（rsync 实际传输的文件，
 * 增量同步时可能少于选中数），为空时回退到 [HistoryRecord.remotePaths]。
 */
class HistoryDetailDialog(
    project: Project,
    private val record: HistoryRecord
) : DialogWrapper(project) {

    init {
        title = DeployXBundle.message("history.detail.dialog.title")
        setOKButtonText(DeployXBundle.message("history.detail.dialog.close"))
        init()
    }

    /** 仅保留“关闭”按钮（OK 按钮），无取消语义。 */
    override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(10), JBUI.scale(10)))
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(420))

        // 顶部：关键信息（标签-值表格）
        panel.add(createDetailsPanel(), BorderLayout.NORTH)

        // 中部：更新文件清单
        panel.add(createFilesPanel(), BorderLayout.CENTER)

        return panel
    }

    /** 关键信息面板：时间/操作/状态/服务器/目录/文件数/耗时/备份/命令。 */
    private fun createDetailsPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        var gridy = 0

        val statusText = when (record.status) {
            HistoryRecord.OperationStatus.SUCCESS -> DeployXBundle.message("report.status.success")
            HistoryRecord.OperationStatus.FAILED -> DeployXBundle.message("report.status.failed")
            HistoryRecord.OperationStatus.CANCELLED -> DeployXBundle.message("toolwindow.history.detail.statusCancelled")
        }
        addRow(panel, DeployXBundle.message("toolwindow.history.detail.time", record.formattedDate), gridy++)
        addRow(panel, DeployXBundle.message("toolwindow.history.detail.operation", record.type.value.uppercase()), gridy++)
        addRow(panel, DeployXBundle.message("toolwindow.history.detail.status", statusText), gridy++)
        addRow(panel, DeployXBundle.message("toolwindow.history.detail.server",
            record.serverName.ifBlank { record.serverId }, record.serverAddress.ifBlank { "-" }), gridy++)
        // PULL 方向相反：远程 -> 本地
        if (record.type == HistoryRecord.OperationType.PULL) {
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.remoteDir", record.targetPath.ifBlank { "-" }), gridy++)
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.localDir", record.sourcePath.ifBlank { "-" }), gridy++)
        } else {
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.localDir", record.sourcePath.ifBlank { "-" }), gridy++)
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.remoteDir", record.targetPath.ifBlank { "-" }), gridy++)
        }
        addRow(panel, DeployXBundle.message("toolwindow.history.detail.fileCount", record.fileCount), gridy++)
        addRow(panel, DeployXBundle.message("toolwindow.history.detail.duration", record.formattedDuration), gridy++)
        if (record.fileSize > 0) {
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.size", record.formattedSize), gridy++)
        }
        if (record.canRollback && record.backupFilePath.isNotBlank()) {
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.backup", record.backupFilePath), gridy++)
        }
        if (record.preCommand.isNotBlank()) {
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.preCommand", record.preCommand), gridy++)
        }
        if (record.postCommand.isNotBlank()) {
            addRow(panel, DeployXBundle.message("toolwindow.history.detail.postCommand", record.postCommand), gridy++)
        }
        return panel
    }

    /** 更新文件清单面板：优先用 transferredFiles，为空回退 remotePaths。 */
    private fun createFilesPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(5), JBUI.scale(5)))

        // 优先展示实际传输的文件；旧记录或无传输时回退到选中的远程路径
        val files = record.transferredFiles.ifEmpty { record.remotePaths }
        val titleKey = if (record.transferredFiles.isNotEmpty()) {
            "toolwindow.history.detail.updatedFilesTitle"
        } else {
            "toolwindow.history.detail.selectedFilesTitle"
        }
        val titleLabel = JBLabel(DeployXBundle.message(titleKey, files.size)).apply {
            border = JBUI.Borders.empty(5, 0)
        }
        panel.add(titleLabel, BorderLayout.NORTH)

        val textArea = JBTextArea().apply {
            isEditable = false
            rows = 10
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12)
            lineWrap = true
            wrapStyleWord = true
        }
        if (files.isNotEmpty()) {
            // 最多显示 200 个，超出提示
            val display = files.take(200)
            textArea.text = display.joinToString("\n")
            if (files.size > 200) {
                textArea.text += "\n\n... ${DeployXBundle.message("history.detail.dialog.moreFiles", files.size - 200)}"
            }
        } else {
            textArea.text = DeployXBundle.message("toolwindow.history.detail.noFiles")
        }
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        return panel
    }

    /** 添加一行信息（单列，整行显示，值可换行）。 */
    private fun addRow(panel: JPanel, text: String, gridy: Int) {
        val gbc = GridBagConstraints().apply {
            this.gridy = gridy
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(JBUI.scale(2), JBUI.scale(2), JBUI.scale(2), JBUI.scale(2))
            anchor = GridBagConstraints.WEST
        }
        panel.add(JBLabel(text).apply {
            // 允许长路径/命令完整显示
            verticalAlignment = SwingConstants.TOP
        }, gbc)
    }
}
