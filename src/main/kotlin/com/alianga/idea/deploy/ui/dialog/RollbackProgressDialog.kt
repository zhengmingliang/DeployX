package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.RollbackResult
import com.alianga.idea.deploy.service.RollbackService
import com.intellij.openapi.application.ApplicationManager
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
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities
import javax.swing.SwingWorker

/**
 * 回滚执行进度对话框
 * 显示回滚执行的进度和日志
 */
class RollbackProgressDialog(
    private val project: Project,
    private val record: HistoryRecord,
    private val onComplete: (RollbackResult) -> Unit
) : DialogWrapper(project, true) {

    private val progressBar = JProgressBar(0, 100)
    private val statusLabel = JBLabel(DeployXBundle.message("rollback.progress.initializing"))
    private val logArea = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, 12)
        lineWrap = true
        wrapStyleWord = true
    }
    private val logList = mutableListOf<String>()
    private var result: RollbackResult? = null

    init {
        title = DeployXBundle.message("rollback.progress.title")
        setOKButtonText(DeployXBundle.message("rollback.progress.close"))
        init()
        isModal = true
        isResizable = true

        // 启动后台任务
        startRollback()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(10), JBUI.scale(10)))
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(JBUI.scale(600), JBUI.scale(400))

        // 顶部信息面板
        val infoPanel = createInfoPanel()
        panel.add(infoPanel, BorderLayout.NORTH)

        // 进度条
        panel.add(progressBar, BorderLayout.CENTER)

        // 日志区域
        val logPanel = createLogPanel()
        panel.add(logPanel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * 创建信息面板
     */
    private fun createInfoPanel(): JComponent {
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

        // 分隔线
        val separatorGbc = gbc.clone() as GridBagConstraints
        separatorGbc.gridx = 0
        separatorGbc.gridy = gridy++
        separatorGbc.gridwidth = 2
        separatorGbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(javax.swing.JSeparator(), separatorGbc)

        // 状态标签
        val statusGbc = gbc.clone() as GridBagConstraints
        statusGbc.gridx = 0
        statusGbc.gridy = gridy++
        statusGbc.gridwidth = 2
        statusGbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(statusLabel, statusGbc)

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
        panel.add(JBLabel("<b>$label:</b>"), gbc)

        // 值
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(JBLabel(value), gbc)
    }

    /**
     * 创建日志面板
     */
    private fun createLogPanel(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(5), JBUI.scale(5)))

        val titleLabel = JBLabel(DeployXBundle.message("rollback.progress.logTitle")).apply {
            border = JBUI.Borders.empty(5, 0)
        }
        panel.add(titleLabel, BorderLayout.NORTH)

        val scrollPane = JBScrollPane(logArea)
        scrollPane.preferredSize = Dimension(JBUI.scale(580), JBUI.scale(200))
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    /**
     * 启动后台回滚任务
     */
    private fun startRollback() {
        // 禁用取消按钮（避免用户中途取消
        setCancelButtonText(DeployXBundle.message("rollback.progress.cancelDisabled"))

        object : SwingWorker<RollbackResult, Pair<Int, String>>() {

            override fun doInBackground(): RollbackResult {
                return RollbackService.getInstance().rollback(
                    record = record,
                    logCallback = { log ->
                        publish(Pair(-1, log))
                    },
                    progressCallback = { progress, message ->
                        publish(Pair(progress, message))
                    }
                )
            }

            override fun process(chunks: List<Pair<Int, String>>) {
                chunks.forEach { (progress, message) ->
                    if (progress >= 0) {
                        updateProgress(progress, message)
                    } else {
                        addLog(message)
                    }
                }
            }

            override fun done() {
                try {
                    result = get()
                    val rollbackResult = result!!
                    if (rollbackResult.success) {
                        updateProgress(100, DeployXBundle.message("rollback.progress.completed"))
                        addLog(DeployXBundle.message("rollback.progress.success"))
                        addLog(DeployXBundle.message("rollback.progress.restoredFiles", rollbackResult.rolledBackFiles.size))
                    } else {
                        addLog(DeployXBundle.message("rollback.progress.failed"))
                        addLog(rollbackResult.error ?: "")
                    }
                    setOKButtonText(DeployXBundle.message("rollback.progress.close"))
                } catch (e: Exception) {
                    addLog(DeployXBundle.message("rollback.progress.exception", e.message ?: ""))
                }
            }
        }.execute()
    }

    /**
     * 更新进度
     */
    private fun updateProgress(progress: Int, message: String) {
        progressBar.value = progress
        statusLabel.text = message
    }

    /**
     * 添加日志
     */
    private fun addLog(log: String) {
        logList.add(log)
        logArea.append(log + "\n")
        // 滚动到底部
        logArea.caretPosition = logArea.document.length
    }

    override fun createActions(): Array<Action> {
        // 只显示 OK 按钮，不显示取消按钮
        return arrayOf(okAction)
    }

    override fun doOKAction() {
        super.doOKAction()
        result?.let { onComplete(it) }
    }
}
