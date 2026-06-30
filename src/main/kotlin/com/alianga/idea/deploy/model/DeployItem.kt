package com.alianga.idea.deploy.model

/**
 * 批量部署项，用于 Deploy 的完整工作流分组执行。
 */
data class DeployItem(
    val localPath: String,
    val isDirectory: Boolean,
    val serverId: String,
    val mappingId: String,
    val sourceBaseDir: String,
    val remoteBaseDir: String,
    val relativePath: String,
    val excludePatterns: List<String> = emptyList(),
    val backupDir: String? = null,
    val backupSource: String? = null,
    val unzipDest: String? = null,
    val preCommand: String? = null,
    val postCommand: String? = null
)
