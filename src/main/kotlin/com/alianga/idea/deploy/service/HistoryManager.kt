package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.config.ConfigManager
import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.RollbackRecord
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
        private const val MAX_ROLLBACK_SIZE = 200

        fun getInstance(): HistoryManager =
            ApplicationManager.getApplication().getService(HistoryManager::class.java)
    }

    private val records = mutableListOf<HistoryRecord>()
    private val rollbackRecords = mutableListOf<RollbackRecord>()

    init {
        loadFromConfig()
    }

    /**
     * 从配置文件加载历史记录
     */
    private fun loadFromConfig() {
        synchronized(records) {
            records.clear()
            records.addAll(ConfigManager.getInstance().loadHistory())
        }
        synchronized(rollbackRecords) {
            rollbackRecords.clear()
            rollbackRecords.addAll(ConfigManager.getInstance().loadRollbackHistory())
        }
        LOG.info("Loaded ${records.size} history records, ${rollbackRecords.size} rollback records")
    }

    /**
     * 获取所有历史记录（按时间倒序）
     */
    fun getRecords(): List<HistoryRecord> = synchronized(records) { records.sortedByDescending { it.timestamp } }

    /**
     * 添加历史记录（线程安全，支持并行部署并发调用）
     */
    fun addRecord(record: HistoryRecord) {
        synchronized(records) {
            records.add(0, record) // 插入到最前面

            // 限制历史记录数量
            if (records.size > MAX_HISTORY_SIZE) {
                records.subList(MAX_HISTORY_SIZE, records.size).clear()
            }

            saveToConfig()
        }
        LOG.info("Added history record: ${record.type} - ${record.sourcePath}")
    }

    /**
     * 删除历史记录
     */
    fun deleteRecord(id: String) {
        synchronized(records) {
            records.removeAll { it.id == id }
            saveToConfig()
        }
        LOG.info("Deleted history record: $id")
    }

    /**
     * 清空历史记录
     */
    fun clearHistory() {
        synchronized(records) {
            records.clear()
            saveToConfig()
        }
        LOG.info("Cleared all history records")
    }

    /**
     * 保存到配置文件
     */
    private fun saveToConfig() {
        ConfigManager.getInstance().saveHistory(records.toList())
    }

    // ========== 回滚记录管理 ==========

    /**
     * 获取所有回滚记录（按时间倒序）
     */
    fun getRollbackRecords(): List<RollbackRecord> = synchronized(rollbackRecords) {
        rollbackRecords.sortedByDescending { it.timestamp }
    }

    /**
     * 获取指定部署记录的回滚历史
     */
    fun getRollbackRecordsForDeploy(deployId: String): List<RollbackRecord> = synchronized(rollbackRecords) {
        rollbackRecords.filter { it.sourceDeployId == deployId }.sortedByDescending { it.timestamp }
    }

    /**
     * 添加回滚记录
     */
    fun addRollbackRecord(record: RollbackRecord) {
        synchronized(rollbackRecords) {
            rollbackRecords.add(0, record)

            // 限制回滚记录数量
            if (rollbackRecords.size > MAX_ROLLBACK_SIZE) {
                rollbackRecords.subList(MAX_ROLLBACK_SIZE, rollbackRecords.size).clear()
            }

            saveRollbackToConfig()
        }
        LOG.info("Added rollback record: ${record.id} - ${record.remoteTargetPath}")
    }

    /**
     * 删除回滚记录
     */
    fun deleteRollbackRecord(id: String) {
        synchronized(rollbackRecords) {
            rollbackRecords.removeAll { it.id == id }
            saveRollbackToConfig()
        }
        LOG.info("Deleted rollback record: $id")
    }

    /**
     * 清空回滚记录
     */
    fun clearRollbackHistory() {
        synchronized(rollbackRecords) {
            rollbackRecords.clear()
            saveRollbackToConfig()
        }
        LOG.info("Cleared all rollback records")
    }

    /**
     * 保存回滚记录到配置文件
     */
    private fun saveRollbackToConfig() {
        ConfigManager.getInstance().saveRollbackHistory(rollbackRecords.toList())
    }
}
