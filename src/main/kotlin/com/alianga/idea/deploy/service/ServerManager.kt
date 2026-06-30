package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.config.ConfigManager
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.ServerConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger

/**
 * 服务器管理器 - 负责服务器配置的 CRUD 操作
 */
@Service
class ServerManager {

    companion object {
        private val LOG = Logger.getInstance(ServerManager::class.java)

        fun getInstance(): ServerManager =
            ApplicationManager.getApplication().getService(ServerManager::class.java)
    }

    private val servers = mutableListOf<ServerConfig>()

    init {
        loadFromConfig()
    }

    /**
     * 从配置文件加载服务器列表
     */
    private fun loadFromConfig() {
        servers.clear()
        servers.addAll(ConfigManager.getInstance().loadServers())
        LOG.info("Loaded ${servers.size} servers from config")
    }

    /**
     * 获取所有服务器
     */
    fun getServers(): List<ServerConfig> = servers.toList()

    /**
     * 根据 ID 获取服务器
     */
    fun getServer(id: String): ServerConfig? =
        servers.firstOrNull { it.id == id }

    /**
     * 获取默认服务器
     */
    fun getDefaultServer(): ServerConfig? {
        val defaultId = FileSyncSettings.getInstance().defaultServerId
        return if (defaultId.isNotEmpty()) {
            getServer(defaultId)
        } else {
            servers.firstOrNull { it.isDefault } ?: servers.firstOrNull()
        }
    }

    /**
     * 添加服务器
     */
    fun addServer(config: ServerConfig) {
        servers.add(config)
        saveToConfig()
        LOG.info("Added server: ${config.id} (${config.name})")
    }

    /**
     * 更新服务器
     */
    fun updateServer(id: String, config: ServerConfig) {
        val index = servers.indexOfFirst { it.id == id }
        if (index >= 0) {
            servers[index] = config
            saveToConfig()
            LOG.info("Updated server: $id")
        }
    }

    /**
     * 删除服务器
     */
    fun deleteServer(id: String) {
        servers.removeAll { it.id == id }
        saveToConfig()
        LOG.info("Deleted server: $id")
    }

    /**
     * 设置默认服务器
     */
    fun setDefaultServer(id: String) {
        FileSyncSettings.getInstance().defaultServerId = id
        LOG.info("Set default server: $id")
    }

    /**
     * 保存到配置文件
     */
    private fun saveToConfig() {
        ConfigManager.getInstance().saveServers(servers)
    }
}
