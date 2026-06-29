package com.alianga.idea.filesync.service

import com.alianga.idea.filesync.config.ConfigManager
import com.alianga.idea.filesync.model.MappingConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File

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

    data class ResolvedMapping(
        val mapping: MappingConfig,
        /** 当前选择路径相对映射本地根目录的路径 */
        val relativePath: String,
        /** rsync 目标目录：文件会上传到该目录，目录会作为子目录上传到该目录 */
        val resolvedRemoteDir: String
    )

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
     * 根据本地路径解析映射，并按相对路径计算远程目标目录。
     *
     * 例：
     * - 映射：local=/target, remote=/app
     * - 选择目录：/target/lib          -> resolvedRemoteDir=/app，rsync 后远端为 /app/lib
     * - 选择文件：/target/lib/a.jar    -> resolvedRemoteDir=/app/lib，rsync 后远端为 /app/lib/a.jar
     */
    fun resolveMappingsByLocalPath(localPath: String, isDirectory: Boolean = File(localPath).isDirectory): List<ResolvedMapping> {
        val normalizedPath = normalizePath(localPath)
        return findMappingsByLocalPath(localPath).map { mapping ->
            val mappingPath = normalizePath(mapping.localDir)
            val relativePath = when {
                normalizedPath == mappingPath -> ""
                normalizedPath.startsWith("$mappingPath/") -> normalizedPath.removePrefix("$mappingPath/").trim('/')
                else -> ""
            }
            val remoteSubDir = parentRelativePath(relativePath)
            val resolvedRemoteDir = joinRemotePath(mapping.remoteDir, remoteSubDir)
            ResolvedMapping(mapping, relativePath, resolvedRemoteDir)
        }
    }

    fun resolveMappingByLocalPath(localPath: String, isDirectory: Boolean = File(localPath).isDirectory): ResolvedMapping? =
        resolveMappingsByLocalPath(localPath, isDirectory).firstOrNull()

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

    private fun parentRelativePath(relativePath: String): String {
        if (relativePath.isBlank()) return ""
        val normalized = relativePath.replace("\\", "/").trim('/')
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) "" else normalized.substring(0, index)
    }

    private fun joinRemotePath(base: String, subDir: String): String {
        val normalizedBase = base.trimEnd('/')
        val normalizedSub = subDir.trim('/')
        return if (normalizedSub.isBlank()) normalizedBase else "$normalizedBase/$normalizedSub"
    }
}
