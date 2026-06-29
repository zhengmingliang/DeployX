package com.alianga.idea.filesync.service

import com.alianga.idea.filesync.config.ConfigManager
import com.alianga.idea.filesync.model.HistoryRecord
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger

/**
 * 历史记录管理器
 */
@Service
class HistoryManager {

    companion object {
        private val LOG = Logger.getInstance(HistoryManager::class.java)
        private const val MAX_HISTORY_SIZE = 1000

        fun getInstance(): HistoryManager =
            ApplicationManager.getApplication().getService(HistoryManager::class.java)
    }

    private val records = mutableListOf<HistoryRecord>()

    init {
        loadFromConfig()
    }

    /**
     * 从配置文件加载历史记录
     */
    private fun loadFromConfig() {
        records.clear()
        records.addAll(ConfigManager.getInstance().loadHistory())
        LOG.info("Loaded ${records.size} history records")
    }

    /**
     * 获取所有历史记录（按时间倒序）
     */
    fun getRecords(): List<HistoryRecord> = records.sortedByDescending { it.timestamp }

    /**
     * 添加历史记录
     */
    fun addRecord(record: HistoryRecord) {
        records.add(0, record) // 插入到最前面

        // 限制历史记录数量
        if (records.size > MAX_HISTORY_SIZE) {
            records.subList(MAX_HISTORY_SIZE, records.size).clear()
        }

        saveToConfig()
        LOG.info("Added history record: ${record.type} - ${record.sourcePath}")
    }

    /**
     * 删除历史记录
     */
    fun deleteRecord(id: String) {
        records.removeAll { it.id == id }
        saveToConfig()
        LOG.info("Deleted history record: $id")
    }

    /**
     * 清空历史记录
     */
    fun clearHistory() {
        records.clear()
        saveToConfig()
        LOG.info("Cleared all history records")
    }

    /**
     * 保存到配置文件
     */
    private fun saveToConfig() {
        ConfigManager.getInstance().saveHistory(records)
    }
}
