package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.service.HistoryManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.*

/**
 * 历史记录面板
 */
class HistoryPanel : JPanel(BorderLayout()) {

    private val historyManager = HistoryManager.getInstance()
    private val listModel = DefaultListModel<String>()
    private val historyList = JBList(listModel)

    init {
        setupUI()
        refreshHistory()
    }

    private fun setupUI() {
        // 工具栏
        val toolbarPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("刷新").apply {
                addActionListener { refreshHistory() }
            })
            add(Box.createHorizontalStrut(8))
            add(JButton("清空").apply {
                addActionListener { clearHistory() }
            })
        }

        add(toolbarPanel, BorderLayout.NORTH)
        add(JBScrollPane(historyList), BorderLayout.CENTER)
    }

    fun refreshHistory() {
        listModel.clear()
        val records = historyManager.getRecords()
        for (record in records) {
            listModel.addElement(formatRecord(record))
        }
    }

    private fun clearHistory() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "确定要清空所有历史记录吗？",
            "清空历史",
            JOptionPane.YES_NO_OPTION
        )
        if (result == JOptionPane.YES_OPTION) {
            historyManager.clearHistory()
            refreshHistory()
        }
    }

    private fun formatRecord(record: HistoryRecord): String {
        return "[${record.formattedTime}] ${record.type.value.uppercase()}: " +
                "${record.sourcePath} -> ${record.serverId}:${record.targetPath} " +
                "(${record.formattedSize}, ${record.formattedDuration}) [${record.status.value}]"
    }
}
