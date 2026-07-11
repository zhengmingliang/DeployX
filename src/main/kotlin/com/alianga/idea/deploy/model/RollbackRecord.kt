package com.alianga.idea.deploy.model

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.*

/**
 * 回滚记录 - 记录每次回滚操作的详细信息
 */
data class RollbackRecord(
    @SerializedName("id")
    val id: String = System.currentTimeMillis().toString(),

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    /** 来源部署记录 ID（从哪次部署回滚的） */
    @SerializedName("source_deploy_id")
    val sourceDeployId: String = "",

    /** 服务器 ID */
    @SerializedName("server_id")
    val serverId: String = "",

    /** 服务器名称（显示用，避免 server 被删后看不到名字） */
    @SerializedName("server_name")
    val serverName: String = "",

    /** 服务器地址（显示用） */
    @SerializedName("server_address")
    val serverAddress: String = "",

    /** 远程目标路径 */
    @SerializedName("remote_target_path")
    val remoteTargetPath: String = "",

    /** 使用的备份文件路径 */
    @SerializedName("backup_file_used")
    val backupFileUsed: String = "",

    /** 回滚恢复的文件列表 */
    @SerializedName("rolled_back_files")
    val rolledBackFiles: List<String> = emptyList(),

    /** 回滚状态 */
    @SerializedName("status")
    val status: HistoryRecord.OperationStatus = HistoryRecord.OperationStatus.SUCCESS,

    /** 错误信息（失败时） */
    @SerializedName("error_message")
    val errorMessage: String? = null,

    /** 执行耗时（毫秒） */
    @SerializedName("duration")
    val duration: Long = 0,

    /** 回滚报告文本 */
    @SerializedName("report_text")
    val reportText: String = ""
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
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

    /** 状态图标 */
    val statusIcon: String
        get() = when (status) {
            HistoryRecord.OperationStatus.SUCCESS -> "✓"
            HistoryRecord.OperationStatus.FAILED -> "✗"
            HistoryRecord.OperationStatus.CANCELLED -> "⊘"
        }

    /** 显示用的摘要文本 */
    val summary: String
        get() = "[$formattedDate] $statusIcon ROLLBACK: $serverName → $remoteTargetPath"
}
