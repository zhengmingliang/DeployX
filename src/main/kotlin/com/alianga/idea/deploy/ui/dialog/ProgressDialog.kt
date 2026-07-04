package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import java.awt.Dimension
import javax.swing.*

/**
 * 进度显示对话框
 */
class ProgressDialog(
    dialogTitle: String
) : DialogWrapper(null) {

    private val progressBar = JProgressBar(0, 100)
    private val currentFileLabel = JBLabel("-")
    private val speedLabel = JBLabel("-")
    private val etaLabel = JBLabel("-")
    private val transferredLabel = JBLabel("-")
    private val logArea = JTextArea()

    init {
        setTitle(dialogTitle)
        setCancelButtonText(DeployXBundle.message("dialog.progress.button.cancel"))
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        logArea.isEditable = false
        logArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)

        val infoPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(DeployXBundle.message("dialog.progress.label.currentFile"), currentFileLabel)
            .addLabeledComponent(DeployXBundle.message("dialog.progress.label.transferSpeed"), speedLabel)
            .addLabeledComponent(DeployXBundle.message("dialog.progress.label.estimatedRemaining"), etaLabel)
            .addLabeledComponent(DeployXBundle.message("dialog.progress.label.transferred"), transferredLabel)
            .panel

        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        mainPanel.add(progressBar)
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(infoPanel)
        mainPanel.add(Box.createVerticalStrut(8))
        mainPanel.add(JBLabel(DeployXBundle.message("dialog.progress.label.log")))
        mainPanel.add(JBScrollPane(logArea).apply {
            preferredSize = Dimension(0, 150)
        })

        mainPanel.preferredSize = Dimension(500, 350)
        return mainPanel
    }

    fun updateProgress(percentage: Int, currentFile: String, speed: String, eta: String, transferred: String) {
        SwingUtilities.invokeLater {
            progressBar.value = percentage
            currentFileLabel.text = currentFile
            speedLabel.text = speed
            etaLabel.text = eta
            transferredLabel.text = transferred
        }
    }

    fun appendLog(message: String) {
        SwingUtilities.invokeLater {
            logArea.append("$message\n")
            logArea.caretPosition = logArea.document.length
        }
    }

    fun setComplete(success: Boolean, message: String) {
        SwingUtilities.invokeLater {
            progressBar.value = if (success) 100 else progressBar.value
            appendLog(message)
        }
    }
}
