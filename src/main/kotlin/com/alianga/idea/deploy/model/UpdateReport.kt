package com.alianga.idea.deploy.model

/**
 * 一次上传/部署操作的更新报告。
 */
data class UpdateReport(
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val operationType: String,
    val groups: List<UpdateReportGroup> = emptyList()
) {
    val success: Boolean
        get() = groups.all { it.success }
}

/**
 * 一个上传/部署分组的报告。
 */
data class UpdateReportGroup(
    val serverId: String,
    val serverName: String = "",
    val serverAddress: String = "",
    val sourceBaseDir: String = "",
    val remoteBaseDir: String = "",
    val selectedLocalPaths: List<String> = emptyList(),
    val relativePaths: List<String> = emptyList(),
    val remotePaths: List<String> = emptyList(),
    val transferredFiles: List<String> = emptyList(),
    val success: Boolean = true,
    val duration: Long = 0,
    val totalSize: Long = 0,
    val rsyncOutput: String = "",
    val backupPath: String? = null
)
