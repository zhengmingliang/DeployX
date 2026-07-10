package com.alianga.idea.deploy.util

import com.alianga.idea.deploy.config.ConfigManager
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.ServerConfigDeserializer
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.ScriptManager
import com.alianga.idea.deploy.service.ServerManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 插件配置导入导出工具（AES-256-GCM 加密）。
 *
 * 导出格式（v1.0）：
 * ```json
 * { "version": "1.0", "encrypted": true, "data": "<Base64(AES/GCM加密的JSON)>" }
 * ```
 *
 * 导出格式（v1.1，包含密钥）：
 * ```json
 * {
 *   "version": "1.1",
 *   "encrypted": true,
 *   "data": "<Base64(AES/GCM加密的JSON)>",
 *   "keys": [
 *     { "serverId": "server1", "fileName": "id_rsa", "content": "<Base64(AES/GCM加密的密钥内容)>" }
 *   ]
 * }
 * ```
 * 解密后的 JSON 包含 servers（含密码）、mappings、scripts 三个数组。
 * `keys` 字段可选，旧版导出文件不含此字段，导入器兼容处理。
 *
 * 加密方案：用户密码 -> PBKDF2WithHmacSHA256 派生 AES 密钥 -> AES/GCM/NoPadding 加密。
 */
object ConfigExporter {

    private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
        .registerTypeAdapter(ServerConfig::class.java, ServerConfigDeserializer)
        .create()
    private const val PBKDF2_ITERATIONS = 65536
    private const val KEY_LENGTH = 256
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    private const val EXPORT_VERSION_WITH_KEYS = "1.1"
    private const val EXPORT_VERSION_BASE = "1.0"

    /** 导入的密钥文件存储目录 */
    private val KEYS_DIR by lazy {
        val dir = File(ConfigManager.getInstance().getConfigDir(), "keys")
        if (!dir.exists()) {
            dir.mkdirs()
            try {
                dir.setReadable(true, false)
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
            } catch (_: Exception) {
                // 设置权限失败，继续运行
            }
        }
        dir
    }

    data class ConfigBundle(
        val servers: List<ServerConfig>,
        val mappings: List<MappingConfig>,
        val scripts: List<ScriptConfig>
    )

    /** 导出文件中的密钥条目 */
    data class KeyFileEntry(
        val serverId: String,
        val fileName: String,
        val content: String  // AES-256-GCM 加密后的 Base64 字符串
    )

    data class ExportResult(
        val success: Boolean,
        val file: File? = null,
        val error: String? = null,
        val keysExported: Int = 0,
        val keysMissing: List<String> = emptyList()
    )

    data class ImportResult(
        val serversAdded: Int,
        val serversUpdated: Int,
        val mappingsAdded: Int,
        val mappingsUpdated: Int,
        val scriptsAdded: Int,
        val scriptsUpdated: Int,
        val keysImported: Int = 0,
        val keyMissingServers: List<String> = emptyList()
    )

    /**
     * 导出所有配置到加密文件。
     *
     * @param outputFile 目标文件
     * @param password 用户输入的加密密码
     * @param exportKeys 是否导出 SSH 密钥文件内容
     * @return 导出结果
     */
    fun exportConfig(outputFile: File, password: String, exportKeys: Boolean = false): ExportResult {
        return try {
            val configManager = ConfigManager.getInstance()
            val servers = ServerManager.getInstance().getServers().map { server ->
                // 从密钥链读取密码，写入导出数据
                val pwd = configManager.loadPassword(server.id) ?: server.password
                server.copy(password = pwd)
            }
            val mappings = MappingManager.getInstance().getMappings()
            val scripts = ScriptManager.getInstance().getScripts()

            val bundle = ConfigBundle(servers, mappings, scripts)
            val json = GSON.toJson(bundle)

            val encrypted = encrypt(json, password)

            // 如需导出密钥，读取并加密
            val keysList = mutableListOf<KeyFileEntry>()
            val missingKeys = mutableListOf<String>()

            if (exportKeys) {
                servers.forEach { server ->
                    if (server.authType == ServerConfig.AuthType.KEY && server.keyFile.isNotBlank()) {
                        val keyFile = File(server.keyFile)
                        if (keyFile.exists() && keyFile.isFile) {
                            try {
                                val keyContent = keyFile.readBytes().toString(Charsets.UTF_8)
                                val encryptedContent = encrypt(keyContent, password)
                                keysList.add(KeyFileEntry(
                                    serverId = server.id,
                                    fileName = keyFile.name,
                                    content = encryptedContent
                                ))
                            } catch (e: Exception) {
                                missingKeys.add("${server.id} (${server.keyFile}): ${e.message}")
                            }
                        } else {
                            missingKeys.add("${server.id} (${server.keyFile}): 文件不存在")
                        }
                    }
                }
            }

            // 构建导出 JSON
            val exportMap = HashMap<String, Any>().apply {
                put("version", if (exportKeys) EXPORT_VERSION_WITH_KEYS else EXPORT_VERSION_BASE)
                put("encrypted", true)
                put("data", encrypted)
                if (exportKeys && keysList.isNotEmpty()) {
                    put("keys", keysList)
                }
            }
            val exportJson = GSON.toJson(exportMap)

            outputFile.writeText(exportJson)
            ExportResult(
                success = true,
                file = outputFile,
                keysExported = keysList.size,
                keysMissing = missingKeys.toList()
            )
        } catch (e: Exception) {
            ExportResult(success = false, error = e.message)
        }
    }

    /**
     * 从加密文件导入配置。
     *
     * @param inputFile 导入文件
     * @param password 解密密码
     * @param overwriteServer ID 冲突时是否覆盖
     * @param overwriteMapping ID 冲突时是否覆盖
     * @param overwriteScript ID 冲突时是否覆盖
     * @return 导入结果
     */
    fun importConfig(
        inputFile: File,
        password: String,
        overwriteServer: Boolean = false,
        overwriteMapping: Boolean = false,
        overwriteScript: Boolean = false
    ): ImportResult {
        val json = inputFile.readText()
        @Suppress("UNCHECKED_CAST")
        val wrapper = GSON.fromJson(json, Map::class.java) as Map<String, Any>
        val encryptedData = wrapper["data"] as String
        val decrypted = decrypt(encryptedData, password)

        val bundle = GSON.fromJson(decrypted, ConfigBundle::class.java)

        // 解析密钥文件（如果存在）
        @Suppress("UNCHECKED_CAST")
        val keysRaw = wrapper["keys"] as? List<Map<String, Any>>
        val keyPathMap = mutableMapOf<String, String>() // serverId -> 本地密钥路径
        val keyMissingServers = mutableListOf<String>()

        if (keysRaw != null && keysRaw.isNotEmpty()) {
            keysRaw.forEach { entry ->
                val serverId = entry["serverId"] as? String ?: return@forEach
                val fileName = entry["fileName"] as? String ?: return@forEach
                val encryptedContent = entry["content"] as? String ?: return@forEach

                try {
                    val decryptedContent = decrypt(encryptedContent, password)
                    val destFile = File(KEYS_DIR, "${serverId}_${fileName}")
                    destFile.writeText(decryptedContent, Charsets.UTF_8)
                    // 设置密钥文件权限为 600（仅所有者可读写）
                    try {
                        destFile.setReadable(true, false)
                        destFile.setWritable(true, false)
                        destFile.setExecutable(false, false)
                    } catch (_: Exception) {
                        // 设置权限失败，继续运行
                    }
                    keyPathMap[serverId] = destFile.absolutePath
                } catch (e: Exception) {
                    keyMissingServers.add("$serverId (${fileName}): ${e.message}")
                }
            }
        }

        val configManager = ConfigManager.getInstance()
        var serversAdded = 0
        var serversUpdated = 0

        // 导入服务器
        val existingServers = ServerManager.getInstance().getServers().associateBy { it.id }
        val serversToSave = bundle.servers.map { imported ->
            val existing = existingServers[imported.id]
            var server = if (existing != null) {
                if (overwriteServer) {
                    serversUpdated++
                    imported
                } else {
                    // 生成新 ID
                    serversAdded++
                    imported.copy(id = "${imported.id}_imported")
                }
            } else {
                serversAdded++
                imported
            }

            // 如果密钥文件中包含该服务器的密钥，更新 keyFile 路径指向本地
            val localKeyPath = keyPathMap[server.id]
            if (localKeyPath != null && server.authType == ServerConfig.AuthType.KEY) {
                server = server.copy(keyFile = localKeyPath)
            } else if (server.authType == ServerConfig.AuthType.KEY && server.keyFile.isNotBlank() && localKeyPath == null) {
                // 密钥认证但未包含密钥文件，记录缺失
                if (server.keyFile.isNotBlank()) {
                    keyMissingServers.add("${server.id}: 密钥文件未包含在导出中")
                }
            }

            server
        }

        // 保存服务器（含密码）。saveServers 会自动：
        // 1. 将密码保存到 PasswordSafe + .passwords.dat
        // 2. 写入 servers.json 时清空 password 字段
        val serverManager = ServerManager.getInstance()
        serversToSave.forEach { server ->
            if (serverManager.getServer(server.id) != null) {
                serverManager.updateServer(server.id, server)
            } else {
                serverManager.addServer(server)
            }
        }

        // 导入映射
        val mappingManager = MappingManager.getInstance()
        val existingMappings = mappingManager.getMappings().associateBy { it.effectiveId }
        var mappingsAdded = 0
        var mappingsUpdated = 0
        bundle.mappings.forEach { imported ->
            val mapped = MappingConfig.ensureId(imported)
            val existing = existingMappings[mapped.effectiveId]
            if (existing != null) {
                if (overwriteMapping) {
                    mappingManager.updateMapping(mapped.effectiveId, mapped)
                    mappingsUpdated++
                } else {
                    mappingManager.addMapping(mapped.copy(id = MappingConfig.generateId()))
                    mappingsAdded++
                }
            } else {
                mappingManager.addMapping(mapped)
                mappingsAdded++
            }
        }

        // 导入脚本（复用 ScriptManager 的导入逻辑）
        val scriptResult = ScriptManager.getInstance().importScripts(bundle.scripts, overwriteScript)

        return ImportResult(
            serversAdded = serversAdded,
            serversUpdated = serversUpdated,
            mappingsAdded = mappingsAdded,
            mappingsUpdated = mappingsUpdated,
            scriptsAdded = scriptResult.added,
            scriptsUpdated = scriptResult.updated,
            keysImported = keyPathMap.size,
            keyMissingServers = keyMissingServers
        )
    }

    /**
     * 检查导入文件中是否有 ID 冲突。
     */
    fun hasIdConflicts(inputFile: File, password: String): Boolean {
        val json = inputFile.readText()
        @Suppress("UNCHECKED_CAST")
        val wrapper = GSON.fromJson(json, Map::class.java) as Map<String, Any>
        val encryptedData = wrapper["data"] as String
        val decrypted = decrypt(encryptedData, password)
        val bundle = GSON.fromJson(decrypted, ConfigBundle::class.java)

        val serverIds = ServerManager.getInstance().getServers().map { it.id }.toSet()
        val mappingIds = MappingManager.getInstance().getMappings().map { it.effectiveId }.toSet()
        val scriptIds = ScriptManager.getInstance().getScripts().map { it.id }.toSet()

        return bundle.servers.any { it.id in serverIds } ||
                bundle.mappings.any { MappingConfig.ensureId(it).effectiveId in mappingIds } ||
                bundle.scripts.any { ScriptConfig.ensureId(it).id in scriptIds }
    }

    // ===== 加密/解密 =====

    private fun encrypt(plaintext: String, password: String): String {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // 拼接 salt + iv + ciphertext，整体 Base64
        val combined = salt + iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encrypted: String, password: String): String {
        val combined = Base64.getDecoder().decode(encrypted)
        val salt = combined.copyOfRange(0, SALT_LENGTH)
        val iv = combined.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val ciphertext = combined.copyOfRange(SALT_LENGTH + IV_LENGTH, combined.size)
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
