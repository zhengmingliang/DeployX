package com.alianga.idea.deploy.util

import com.alianga.idea.deploy.config.ConfigManager
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.MappingManager
import com.alianga.idea.deploy.service.ScriptManager
import com.alianga.idea.deploy.service.ServerManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * 插件配置导入导出工具（AES-256-GCM 加密）。
 *
 * 导出格式：
 * ```json
 * { "version": "1.0", "encrypted": true, "data": "<Base64(AES/GCM加密的JSON)>" }
 * ```
 * 解密后的 JSON 包含 servers（含密码）、mappings、scripts 三个数组。
 *
 * 加密方案：用户密码 -> PBKDF2WithHmacSHA256 派生 AES 密钥 -> AES/GCM/NoPadding 加密。
 */
object ConfigExporter {

    private val GSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private const val PBKDF2_ITERATIONS = 65536
    private const val KEY_LENGTH = 256
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    data class ConfigBundle(
        val servers: List<ServerConfig>,
        val mappings: List<MappingConfig>,
        val scripts: List<ScriptConfig>
    )

    data class ExportResult(val success: Boolean, val file: File? = null, val error: String? = null)
    data class ImportResult(val serversAdded: Int, val serversUpdated: Int, val mappingsAdded: Int, val mappingsUpdated: Int, val scriptsAdded: Int, val scriptsUpdated: Int)

    /**
     * 导出所有配置到加密文件。
     *
     * @param outputFile 目标文件
     * @param password 用户输入的加密密码
     * @return 导出结果
     */
    fun exportConfig(outputFile: File, password: String): ExportResult {
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
            val exportJson = GSON.toJson(mapOf(
                "version" to "1.0",
                "encrypted" to true,
                "data" to encrypted
            ))

            outputFile.writeText(exportJson)
            ExportResult(success = true, file = outputFile)
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

        val configManager = ConfigManager.getInstance()
        var serversAdded = 0
        var serversUpdated = 0

        // 导入服务器
        val existingServers = ServerManager.getInstance().getServers().associateBy { it.id }
        val serversToSave = bundle.servers.map { imported ->
            val existing = existingServers[imported.id]
            if (existing != null) {
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
        }
        // 保存密码到密钥链
        serversToSave.forEach { server ->
            if (server.password.isNotBlank()) {
                configManager.savePassword(server.id, server.password)
            }
        }
        // 保存服务器（不含密码）
        val serverManager = ServerManager.getInstance()
        serversToSave.forEach { server ->
            val sanitized = server.copy(password = "")
            if (serverManager.getServer(server.id) != null) {
                serverManager.updateServer(server.id, sanitized)
            } else {
                serverManager.addServer(sanitized)
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
            scriptsUpdated = scriptResult.updated
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
