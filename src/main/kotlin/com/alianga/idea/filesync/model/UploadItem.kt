package com.alianga.idea.filesync.model

/**
 * 批量上传项，用于 Sync / Quick Push 的 upload-only 模式。
 */
data class UploadItem(
    val localPath: String,
    val isDirectory: Boolean,
    val serverId: String,
    val mappingId: String,
    val sourceBaseDir: String,
    val remoteBaseDir: String,
    val relativePath: String,
    val excludePatterns: List<String> = emptyList(),
    val preCommand: String? = null,
    val postCommand: String? = null
)
