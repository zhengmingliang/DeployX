package com.alianga.idea.deploy.model

/**
 * 部署请求
 */
data class DeployRequest(
    val localPath: String,
    val serverId: String,
    val remotePath: String,
    /** 备份目录（备份到哪里） */
    val backupDir: String? = null,
    /** 备份源（备份什么，可选，不填则自动推断） */
    val backupSource: String? = null,
    val unzipDest: String? = null,
    val excludePatterns: List<String> = emptyList(),
    /** 上传前在远程服务器执行的命令 */
    val preCommand: String? = null,
    /** 上传后在远程服务器执行的命令 */
    val postCommand: String? = null
)
