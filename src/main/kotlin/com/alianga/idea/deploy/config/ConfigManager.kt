package com.alianga.idea.deploy.config

import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ServerConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * 配置管理器 - 负责 JSON 配置文件的读写
 * 配置存储目录: ~/.deploy-x/
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

    // ==================== 服务器配置 ====================

    /**
     * 读取所有服务器配置
     */
    fun loadServers(): List<ServerConfig> {
        if (!SERVERS_FILE.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            GSON.fromJson(SERVERS_FILE.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            LOG.warn("Failed to load servers config", e)
            emptyList()
        }
    }

    /**
     * 保存所有服务器配置
     */
    fun saveServers(servers: List<ServerConfig>) {
        try {
            SERVERS_FILE.writeText(GSON.toJson(servers))
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
