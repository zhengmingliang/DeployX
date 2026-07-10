package com.alianga.idea.deploy.model

/**
 * 下载（PULL）执行项 - 表示从远程服务器下载到本地的一个文件/目录
 */
data class DownloadItem(
    /** 本地目标路径 */
    val localPath: String,
    /** 是否为目录 */
    val isDirectory: Boolean,
    /** 服务器 ID */
    val serverId: String,
    /** 关联的映射 ID */
    val mappingId: String,
    /** 本地基础目录（用于相对路径计算） */
    val localBaseDir: String,
    /** 远程基础目录 */
    val remoteBaseDir: String,
    /** 相对路径 */
    val relativePath: String,
    /** 排除模式列表 */
    val excludePatterns: List<String> = emptyList()
) {
    /** 完整远程路径 */
    val fullRemotePath: String
        get() = if (relativePath.isBlank()) {
            remoteBaseDir
        } else {
            remoteBaseDir.trimEnd('/') + "/" + relativePath.trim('/')
        }
}
