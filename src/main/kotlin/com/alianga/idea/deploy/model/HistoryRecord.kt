package com.alianga.idea.deploy.model

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录 - 包含完整部署配置，支持一键重新部署
 */
data class HistoryRecord(
    @SerializedName("id")
    val id: String = System.currentTimeMillis().toString(),

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("type")
    val type: OperationType = OperationType.SYNC,

    @SerializedName("source_path")
    val sourcePath: String = "",

    @SerializedName("server_id")
    val serverId: String = "",

    @SerializedName("target_path")
    val targetPath: String = "",

    @SerializedName("file_count")
    val fileCount: Int = 0,

    @SerializedName("file_size")
    val fileSize: Long = 0,

    @SerializedName("duration")
    val duration: Long = 0,

    @SerializedName("status")
    val status: OperationStatus = OperationStatus.SUCCESS,

    // 保存完整部署配置，用于一键重新部署
    @SerializedName("backup_dir")
    val backupDir: String = "",

    @SerializedName("unzip_dest")
    val unzipDest: String = "",

    @SerializedName("exclude_patterns")
    val excludePatterns: List<String> = emptyList(),

    @SerializedName("pre_command")
    val preCommand: String = "",

    @SerializedName("post_command")
    val postCommand: String = "",

    @SerializedName("relative_paths")
    val relativePaths: List<String> = emptyList(),

    @SerializedName("remote_paths")
    val remotePaths: List<String> = emptyList(),

    @SerializedName("server_name")
    val serverName: String = "",

    @SerializedName("server_address")
    val serverAddress: String = "",

    @SerializedName("report_text")
    val reportText: String = "",

    /** 是否可回滚（部署时生成了备份） */
    @SerializedName("can_rollback")
    val canRollback: Boolean = false,

    /** 备份文件的完整路径（用于回滚） */
    @SerializedName("backup_file_path")
    val backupFilePath: String = ""
) {
    enum class OperationType(val value: String) {
        @SerializedName("sync")
        SYNC("sync"),

        @SerializedName("upload")
        UPLOAD("upload"),

        @SerializedName("deploy")
        DEPLOY("deploy"),

        @SerializedName("pull")
        PULL("pull"),

        @SerializedName("backup")
        BACKUP("backup"),

        @SerializedName("script")
        SCRIPT("script");

        companion object {
            fun fromValue(value: String): OperationType =
                entries.firstOrNull { it.value == value } ?: SYNC
        }
    }

    enum class OperationStatus(val value: String) {
        @SerializedName("success")
        SUCCESS("success"),

        @SerializedName("failed")
        FAILED("failed"),

        @SerializedName("cancelled")
        CANCELLED("cancelled");

        companion object {
            fun fromValue(value: String): OperationStatus =
                entries.firstOrNull { it.value == value } ?: SUCCESS
        }
    }

    /** 从历史记录还原 DeployRequest，用于重新部署 */
    fun toDeployRequest(): DeployRequest {
        return DeployRequest(
            localPath = sourcePath,
            serverId = serverId,
            remotePath = targetPath,
            backupDir = backupDir.ifEmpty { null },
            unzipDest = unzipDest.ifEmpty { null },
            excludePatterns = excludePatterns,
            preCommand = preCommand.ifEmpty { null },
            postCommand = postCommand.ifEmpty { null }
        )
    }

    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val formattedDuration: String
        get() {
            val seconds = duration / 1000
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }

    val formattedSize: String
        get() {
            return when {
                fileSize < 1024 -> "${fileSize}B"
                fileSize < 1024 * 1024 -> "${fileSize / 1024}KB"
                fileSize < 1024 * 1024 * 1024 -> "${fileSize / (1024 * 1024)}MB"
                else -> "${fileSize / (1024 * 1024 * 1024)}GB"
            }
        }

    /** 显示用的摘要文本 */
    val summary: String
        get() {
            val statusIcon = when (status) {
                OperationStatus.SUCCESS -> "✓"
                OperationStatus.FAILED -> "✗"
                OperationStatus.CANCELLED -> "⊘"
            }
            // PULL（从服务器拉取）方向相反：远程源 → 本地目标
            val direction = if (type == OperationType.PULL) {
                "$serverId:$targetPath → $sourcePath"
            } else {
                "$sourcePath → $serverId:$targetPath"
            }
            return "[$formattedDate] $statusIcon ${type.value.uppercase()}: $direction"
        }
}
