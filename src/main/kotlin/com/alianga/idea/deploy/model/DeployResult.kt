package com.alianga.idea.deploy.model

/**
 * 部署结果
 */
data class DeployResult(
    val success: Boolean,
    val taskId: String = "",
    val backupPath: String? = null,
    val transferredFiles: Int = 0,
    val totalSize: Long = 0,
    val duration: Long = 0,
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val reportGroup: UpdateReportGroup? = null
)
