package com.alianga.idea.filesync.model

/**
 * 同步结果
 */
data class SyncResult(
    val success: Boolean,
    val transferredFiles: Int = 0,
    val totalSize: Long = 0,
    val duration: Long = 0,
    val error: String? = null,
    val output: String = "",
    val reportGroup: UpdateReportGroup? = null
)
