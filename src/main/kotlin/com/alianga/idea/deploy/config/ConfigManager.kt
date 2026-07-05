package com.alianga.idea.deploy.config

import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ServerConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * 配置管理器 - 负责 JSON 配置文件的读写
 * 配置存储目录: ~/.deploy-x/
 *
 * 服务器密码安全存储：
 * - 密码通过 IntelliJ PasswordSafe 加密存储，不写入 JSON 明文
 * - 兼容旧版 servers.json 中的明文密码：首次加载时自动迁移到 PasswordSafe
 * - 内存中 ServerConfig.password 始终持有真实密码，调用方无感知
 */
@Service
class ConfigManager {

    companion object {
        private val LOG = Logger.getInstance(ConfigManager::class.java)
        private val CONFIG_DIR = File(System.getProperty("user.home"), ".deploy-x")
        private val SERVERS_FILE = File(CONFIG_DIR, "servers.json")
        private val MAPPINGS_FILE = File(CONFIG_DIR, "mappings.json")
        private val HISTORY_FILE = File(CONFIG_DIR, "history.json")
        private val SCRIPTS_FILE = File(CONFIG_DIR, "scripts.json")

        /** PasswordSafe 服务名，用于区分不同凭据 */
        private const val PASSWORD_SERVICE_NAME = "DeployX.Server.password"

        private val GSON: Gson = GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()

        fun getInstance(): ConfigManager =
            ApplicationManager.getApplication().getService(ConfigManager::class.java)
    }

    init {
        ensureConfigDir()
    }

    /**
     * 确保配置目录存在
     */
    private fun ensureConfigDir() {
        if (!CONFIG_DIR.exists()) {
            CONFIG_DIR.mkdirs()
            LOG.info("Created config directory: ${CONFIG_DIR.absolutePath}")
        }
    }

    // ==================== 密码安全存储 ====================

    /**
     * 构建服务器密码的 CredentialAttributes
     * @param serverId 服务器 ID，作为凭据的唯一标识
     */
    private fun passwordAttributes(serverId: String): CredentialAttributes =
        CredentialAttributes(PASSWORD_SERVICE_NAME, serverId)

    /**
     * 获取 PasswordSafe 实例（通过 Application Service）
     */
    private val passwordSafe: PasswordSafe?
        get() = ApplicationManager.getApplication().getService(PasswordSafe::class.java)

    /**
     * 从 PasswordSafe 加载服务器密码
     */
    private fun loadPassword(serverId: String): String? {
        return try {
            val ps = passwordSafe ?: return null
            ps.getPassword(passwordAttributes(serverId))
        } catch (e: Exception) {
            LOG.warn("Failed to load password for server $serverId from PasswordSafe", e)
            null
        }
    }

    /**
     * 保存服务器密码到 PasswordSafe
     */
    private fun savePassword(serverId: String, password: String) {
        try {
            val ps = passwordSafe ?: return
            ps.setPassword(passwordAttributes(serverId), password)
        } catch (e: Exception) {
            LOG.warn("Failed to save password for server $serverId to PasswordSafe", e)
        }
    }

    /**
     * 删除服务器密码（删除服务器时调用，清理凭据）
     */
    fun removePassword(serverId: String) {
        try {
            val ps = passwordSafe ?: return
            ps.setPassword(passwordAttributes(serverId), null)
        } catch (e: Exception) {
            LOG.warn("Failed to remove password for server $serverId from PasswordSafe", e)
        }
    }

    // ==================== 服务器配置 ====================

    /**
     * 读取所有服务器配置
     *
     * 兼容逻辑：
     * - 旧版 servers.json 中可能存有明文 password 字段，首次加载时自动迁移到 PasswordSafe
     * - 迁移后 JSON 中的 password 被清空（通过触发 saveServers 重新写入）
     * - 新版 JSON 中 password 为空，密码从 PasswordSafe 加载
     */
    fun loadServers(): List<ServerConfig> {
        if (!SERVERS_FILE.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            val rawList: List<ServerConfig> = GSON.fromJson(SERVERS_FILE.readText(), type) ?: emptyList()

            var needsMigration = false
            val result = rawList.map { server ->
                if (server.password.isNotEmpty()) {
                    // 旧版明文密码：迁移到 PasswordSafe
                    LOG.info("Migrating password for server ${server.id} from plaintext to PasswordSafe")
                    savePassword(server.id, server.password)
                    needsMigration = true
                    server  // 内存中保持原密码，调用方无感知
                } else {
                    // 新版：从 PasswordSafe 加载密码
                    val pwd = loadPassword(server.id)
                    if (pwd != null) server.copy(password = pwd) else server
                }
            }

            // 如果有迁移，重新保存以清空 JSON 中的明文密码
            if (needsMigration) {
                LOG.info("Password migration completed, rewriting servers.json to remove plaintext passwords")
                saveServers(result)
            }

            result
        } catch (e: Exception) {
            LOG.warn("Failed to load servers config", e)
            emptyList()
        }
    }

    /**
     * 保存所有服务器配置
     *
     * 密码处理：
     * - 密码单独存到 PasswordSafe，不写入 JSON 明文
     * - 写入 JSON 时 password 字段置空，避免明文泄露
     */
    fun saveServers(servers: List<ServerConfig>) {
        try {
            // 把密码存到 PasswordSafe
            servers.forEach { server ->
                if (server.password.isNotEmpty()) {
                    savePassword(server.id, server.password)
                }
            }
            // 写入 JSON 时清空 password 字段（避免明文存储）
            val sanitized = servers.map { it.copy(password = "") }
            SERVERS_FILE.writeText(GSON.toJson(sanitized))
            LOG.info("Saved ${servers.size} servers to config")
        } catch (e: Exception) {
            LOG.error("Failed to save servers config", e)
        }
    }

    // ==================== 映射配置 ====================

    /**
     * 读取所有映射配置
     */
    fun loadMappings(): List<MappingConfig> {
        if (!MAPPINGS_FILE.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<MappingConfig>>() {}.type
            GSON.fromJson(MAPPINGS_FILE.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            LOG.warn("Failed to load mappings config", e)
            emptyList()
        }
    }

    /**
     * 保存所有映射配置
     */
    fun saveMappings(mappings: List<MappingConfig>) {
        try {
            MAPPINGS_FILE.writeText(GSON.toJson(mappings))
            LOG.info("Saved ${mappings.size} mappings to config")
        } catch (e: Exception) {
            LOG.error("Failed to save mappings config", e)
        }
    }

    // ==================== 历史记录 ====================

    /**
     * 读取历史记录
     */
    fun loadHistory(): List<HistoryRecord> {
        if (!HISTORY_FILE.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryRecord>>() {}.type
            GSON.fromJson(HISTORY_FILE.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            LOG.warn("Failed to load history", e)
            emptyList()
        }
    }

    /**
     * 保存历史记录
     */
    fun saveHistory(records: List<HistoryRecord>) {
        try {
            HISTORY_FILE.writeText(GSON.toJson(records))
            LOG.info("Saved ${records.size} history records")
        } catch (e: Exception) {
            LOG.error("Failed to save history", e)
        }
    }

    // ==================== 脚本配置 ====================

    /**
     * 读取所有脚本配置
     */
    fun loadScripts(): List<ScriptConfig> {
        if (!SCRIPTS_FILE.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ScriptConfig>>() {}.type
            GSON.fromJson(SCRIPTS_FILE.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            LOG.warn("Failed to load scripts config", e)
            emptyList()
        }
    }

    /**
     * 保存所有脚本配置
     */
    fun saveScripts(scripts: List<ScriptConfig>) {
        try {
            SCRIPTS_FILE.writeText(GSON.toJson(scripts))
            LOG.info("Saved ${scripts.size} scripts to config")
        } catch (e: Exception) {
            LOG.error("Failed to save scripts config", e)
        }
    }

    /**
     * 获取配置目录路径
     */
    fun getConfigDir(): String = CONFIG_DIR.absolutePath
}
