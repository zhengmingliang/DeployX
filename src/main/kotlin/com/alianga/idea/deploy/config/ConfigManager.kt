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
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

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
        /** 加密的密码备份文件（Base64 编码的 AES 加密数据） */
        private val PASSWORD_BACKUP_FILE = File(CONFIG_DIR, ".passwords.dat")

        /** PasswordSafe 服务名，用于区分不同凭据 */
        private const val PASSWORD_SERVICE_NAME = "DeployX.Server.password"

        /** 加密密钥派生盐（插件专属固定值） */
        private val ENCRYPTION_SALT = "DeployX-Plugin-Secret-Key-2024".toByteArray()

        private val GSON: Gson = GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()

        fun getInstance(): ConfigManager =
            ApplicationManager.getApplication().getService(ConfigManager::class.java)

        // ==================== AES 加密工具 ====================

        /** 从用户唯一标识派生加密密钥 */
        private fun getEncryptionKey(): SecretKeySpec {
            // 使用 user.home + user.name 作为基础，每个用户有不同的加密密钥
            val userKey = (System.getProperty("user.home") + System.getProperty("user.name")).toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val keyBytes = md.digest(userKey + ENCRYPTION_SALT)
            return SecretKeySpec(keyBytes, "AES")
        }

        /** AES 加密字符串 */
        private fun encrypt(value: String): String {
            return try {
                val cipher = Cipher.getInstance("AES")
                cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey())
                val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                Base64.getEncoder().encodeToString(encrypted)
            } catch (e: Exception) {
                LOG.error("Encryption failed", e)
                ""
            }
        }

        /** AES 解密字符串 */
        private fun decrypt(encrypted: String): String? {
            return try {
                val cipher = Cipher.getInstance("AES")
                cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey())
                val decoded = Base64.getDecoder().decode(encrypted)
                val decrypted = cipher.doFinal(decoded)
                String(decrypted, Charsets.UTF_8)
            } catch (e: Exception) {
                LOG.warn("Decryption failed, data may be corrupted or from another user", e)
                null
            }
        }

        /** 从加密备份文件加载所有密码 */
        private fun loadPasswordBackup(): Map<String, String> {
            return try {
                if (!PASSWORD_BACKUP_FILE.exists()) {
                    emptyMap()
                } else {
                    val content = PASSWORD_BACKUP_FILE.readText()
                    val decrypted = decrypt(content)
                    if (decrypted != null) {
                        GSON.fromJson(decrypted, object : TypeToken<Map<String, String>>() {}.type) ?: emptyMap()
                    } else {
                        emptyMap()
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load password backup", e)
                emptyMap()
            }
        }

        /** 保存所有密码到加密备份文件 */
        private fun savePasswordBackup(passwords: Map<String, String>) {
            try {
                val json = GSON.toJson(passwords)
                val encrypted = encrypt(json)
                PASSWORD_BACKUP_FILE.writeText(encrypted)
            } catch (e: Exception) {
                LOG.error("Failed to save password backup", e)
            }
        }
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

    /** 内存密码缓存（加速频繁访问） */
    private val passwordCache = mutableMapOf<String, String>()

    /**
     * 从 PasswordSafe + 加密备份文件加载服务器密码
     * 双层存储策略：
     * 1. 优先从内存缓存加载
     * 2. 然后尝试从系统密钥链 (PasswordSafe) 加载
     * 3. 最后从加密本地备份文件加载
     */
    fun loadPassword(serverId: String): String? {
        // 1. 优先从内存缓存加载
        passwordCache[serverId]?.let {
            LOG.debug("Loaded password for server $serverId from memory cache")
            return it
        }

        // 2. 尝试从系统密钥链加载
        var password: String? = null
        try {
            val ps = passwordSafe
            if (ps != null) {
                password = ps.getPassword(passwordAttributes(serverId))
                if (password != null) {
                    LOG.debug("Loaded password for server $serverId from PasswordSafe (system keychain)")
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to load password from PasswordSafe for server $serverId", e)
        }

        // 3. 如果系统密钥链加载失败，尝试从加密备份文件加载
        if (password == null) {
            try {
                val backups = loadPasswordBackup()
                password = backups[serverId]
                if (password != null) {
                    LOG.info("Loaded password for server $serverId from encrypted backup file")
                    // 重新保存到 PasswordSafe，尝试恢复系统密钥链存储
                    savePasswordToKeychain(serverId, password)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to load password from backup for server $serverId", e)
            }
        }

        // 缓存到内存
        if (password != null) {
            passwordCache[serverId] = password
        }

        return password
    }

    /**
     * 保存密码到系统密钥链 (PasswordSafe)
     */
    private fun savePasswordToKeychain(serverId: String, password: String) {
        try {
            val ps = passwordSafe ?: return
            ps.setPassword(passwordAttributes(serverId), password)
            LOG.debug("Password saved to PasswordSafe for server $serverId")
        } catch (e: Exception) {
            LOG.warn("Failed to save password to PasswordSafe for server $serverId", e)
        }
    }

    /**
     * 保存服务器密码（双层存储策略）
     * 1. 保存到系统密钥链 (PasswordSafe)
     * 2. 保存到加密本地备份文件
     * 3. 缓存到内存
     */
    fun savePassword(serverId: String, password: String) {
        // 1. 保存到系统密钥链
        savePasswordToKeychain(serverId, password)

        // 2. 更新内存缓存
        passwordCache[serverId] = password

        // 3. 更新加密备份文件
        try {
            val backups = loadPasswordBackup().toMutableMap()
            backups[serverId] = password
            savePasswordBackup(backups)
            LOG.debug("Password backup updated for server $serverId")
        } catch (e: Exception) {
            LOG.error("Failed to update password backup for server $serverId", e)
        }
    }

    /**
     * 删除服务器密码（删除服务器时调用，清理所有存储层）
     * 1. 从系统密钥链删除
     * 2. 从加密备份文件删除
     * 3. 从内存缓存删除
     */
    fun removePassword(serverId: String) {
        // 1. 从系统密钥链删除
        try {
            val ps = passwordSafe
            if (ps != null) {
                ps.setPassword(passwordAttributes(serverId), null)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to remove password from PasswordSafe for server $serverId", e)
        }

        // 2. 从加密备份文件删除
        try {
            val backups = loadPasswordBackup().toMutableMap()
            if (backups.remove(serverId) != null) {
                savePasswordBackup(backups)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to remove password from backup for server $serverId", e)
        }

        // 3. 从内存缓存删除
        passwordCache.remove(serverId)
        LOG.debug("Password removed from all storage layers for server $serverId")
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
