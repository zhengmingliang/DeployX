package com.alianga.idea.filesync.service

import com.alianga.idea.filesync.config.ConfigManager
import com.alianga.idea.filesync.model.MappingConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger

/**
 * 映射管理器 - 负责目录映射配置的 CRUD 操作（基于唯一ID）
 */
@Service
class MappingManager {

    companion object {
        private val LOG = Logger.getInstance(MappingManager::class.java)

        fun getInstance(): MappingManager =
            ApplicationManager.getApplication().getService(MappingManager::class.java)
    }

    private val mappings = mutableListOf<MappingConfig>()

    init {
        loadFromConfig()
    }

    /**
     * 从配置文件加载映射列表，自动为旧数据补全ID
     */
    private fun loadFromConfig() {
        mappings.clear()
        val loaded = ConfigManager.getInstance().loadMappings()
        // 兼容旧数据：自动补全ID
        val migrated = loaded.map { MappingConfig.ensureId(it) }
        mappings.addAll(migrated)
        // 如果有迁移的数据，立即保存
        if (migrated.any { it.id.startsWith("legacy_") }) {
            saveToConfig()
            LOG.info("Migrated ${migrated.size} mappings with legacy IDs")
        }
        LOG.info("Loaded ${mappings.size} mappings from config")
    }

    fun getMappings(): List<MappingConfig> = mappings.toList()

    /**
     * 根据ID获取映射
     */
    fun getMappingById(id: String): MappingConfig? =
        mappings.firstOrNull { it.effectiveId == id }

    /**
     * 根据本地路径查找映射（返回所有匹配的映射）
     */
    fun findMappingsByLocalPath(localPath: String): List<MappingConfig> {
        val normalizedPath = normalizePath(localPath)
        return mappings.filter { mapping ->
            val mappingPath = normalizePath(mapping.localDir)
            normalizedPath == mappingPath || normalizedPath.startsWith("$mappingPath/")
        }
    }

    /**
     * 根据本地路径查找第一个匹配的映射
     */
    fun findMappingByLocalPath(localPath: String): MappingConfig? =
        findMappingsByLocalPath(localPath).firstOrNull()

    /**
     * 添加映射（自动生成ID）
     */
    fun addMapping(config: MappingConfig): MappingConfig {
        val withId = if (config.id.isBlank()) {
            config.copy(id = MappingConfig.generateId())
        } else {
            config
        }
        mappings.add(withId)
        saveToConfig()
        LOG.info("Added mapping: ${withId.name} (id=${withId.id})")
        return withId
    }

    /**
     * 根据ID更新映射
     */
    fun updateMapping(id: String, config: MappingConfig) {
        val index = mappings.indexOfFirst { it.effectiveId == id }
        if (index >= 0) {
            mappings[index] = config
            saveToConfig()
            LOG.info("Updated mapping: $id")
        }
    }

    /**
     * 根据ID删除映射
     */
    fun deleteMapping(id: String) {
        mappings.removeAll { it.effectiveId == id }
        saveToConfig()
        LOG.info("Deleted mapping: $id")
    }

    private fun saveToConfig() {
        ConfigManager.getInstance().saveMappings(mappings)
    }

    private fun normalizePath(path: String): String {
        return path.replace("\\", "/").trimEnd('/')
    }
}
