package com.alianga.idea.deploy.model

/**
 * 同步结果
 */
data class SyncResult(
    val success: Boolean,
    val transferredFiles: Int = 0,
    val transferredFileList: List<String> = emptyList(),
    val totalSize: Long = 0,
    val duration: Long = 0,
    val error: String? = null,
    val output: String = "",
    val reportGroup: UpdateReportGroup? = null,
    /** 实际尝试次数（含首次，自动重试时 >1） */
    val attempts: Int = 1
)
