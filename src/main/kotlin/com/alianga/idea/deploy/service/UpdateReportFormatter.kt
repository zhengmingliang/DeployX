package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.UpdateReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 更新报告 Markdown 格式化器 - 优化版
 */
object UpdateReportFormatter {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun format(report: UpdateReport): String {
        val time = timeFormat.format(Date(report.timestamp))
        val status = if (report.success) DeployXBundle.message("report.status.success") else DeployXBundle.message("report.status.failed")
        val operation = when (report.operationType) {
            "DEPLOY" -> DeployXBundle.message("report.operation.deploy")
            "UPLOAD" -> DeployXBundle.message("report.operation.upload")
            "SYNC" -> DeployXBundle.message("report.operation.sync")
            "PULL" -> DeployXBundle.message("report.operation.pull")
            else -> report.operationType
        }

        return buildString {
            // 标题
            appendLine(DeployXBundle.message("report.header"))
            appendLine()

            // 摘要信息
            appendLine(DeployXBundle.message("report.tableHeader"))
            appendLine(DeployXBundle.message("report.tableDivider"))
            appendLine(DeployXBundle.message("report.row.time", time))
            appendLine(DeployXBundle.message("report.row.operation", operation))
            appendLine(DeployXBundle.message("report.row.status", status))
            appendLine(DeployXBundle.message("report.row.groupCount", report.groups.size))

            // 汇总信息
            val totalTransferredFiles = report.groups.sumOf { it.transferredFiles.size }
            val totalSelectedFiles = report.groups.sumOf { it.remotePaths.size }
            val totalDuration = report.groups.sumOf { it.duration }
            val totalSize = report.groups.sumOf { it.totalSize }

            // 文件数：有实际传输时显示传输数，否则显示选中数（让用户知道本次涉及多少文件）
            val displayFileCount = if (totalTransferredFiles > 0) totalTransferredFiles else totalSelectedFiles
            appendLine(DeployXBundle.message("report.row.totalFiles", displayFileCount))
            appendLine(DeployXBundle.message("report.row.totalDuration", formatDuration(totalDuration)))
            appendLine(DeployXBundle.message("report.row.totalSize", formatBytes(totalSize)))
            appendLine()

            // 分组详情
            if (report.groups.isNotEmpty()) {
                appendLine("---")
                appendLine()

                report.groups.forEachIndexed { index, group ->
                    appendLine(DeployXBundle.message("report.groupTitle", index + 1))
                    appendLine()
                    appendLine(DeployXBundle.message("report.tableHeader"))
                    appendLine(DeployXBundle.message("report.tableDivider"))
                    appendLine(DeployXBundle.message("report.row.server", group.serverName.ifBlank { group.serverId }))
                    appendLine(DeployXBundle.message("report.row.address", group.serverAddress))
                    appendLine(DeployXBundle.message("report.row.status", if (group.success) DeployXBundle.message("report.status.success") else DeployXBundle.message("report.status.failed")))
                    appendLine(DeployXBundle.message("report.row.localDir", group.sourceBaseDir))
                    appendLine(DeployXBundle.message("report.row.remoteDir", group.remoteBaseDir))

                    // 文件数：有实际传输时显示传输数，否则显示选中数
                    val fileCount = if (group.transferredFiles.isNotEmpty()) group.transferredFiles.size else group.remotePaths.size
                    appendLine(DeployXBundle.message("report.row.fileCount", fileCount))
                    appendLine(DeployXBundle.message("report.row.duration", formatDuration(group.duration)))
                    if (group.totalSize > 0) {
                        appendLine(DeployXBundle.message("report.row.totalSize", formatBytes(group.totalSize)))
                    }

                    // 显示备份路径
                    if (!group.backupPath.isNullOrBlank()) {
                        appendLine(DeployXBundle.message("report.row.backupPath", group.backupPath))
                    }
                    appendLine()

                    // 备份信息说明
                    if (!group.backupPath.isNullOrBlank()) {
                        appendLine(DeployXBundle.message("report.backupInfoTitle"))
                        appendLine()
                        appendLine(DeployXBundle.message("report.backupInfoPath", group.backupPath))
                        appendLine()
                    }

                    // 文件列表：仅展示 rsync 实际传输的文件。
                    // 增量同步跳过所有文件时 transferredFiles 为空，显示"无文件变更"提示，
                    // 不再把选中的目录或未变更的文件误展示为"实际更新的文件"。
                    if (group.transferredFiles.isNotEmpty()) {
                        appendLine(DeployXBundle.message("report.updatedFilesTitle"))
                        appendLine()
                        appendLine("```text")
                        group.transferredFiles.forEach { appendLine(it) }
                        appendLine("```")
                        appendLine()
                    } else {
                        appendLine(DeployXBundle.message("report.noFilesChanged"))
                        appendLine()
                    }
                }
            }

            // 备注
            appendLine("---")
            appendLine()
            appendLine(DeployXBundle.message("report.footer", time))
        }
    }

    /**
     * 格式化字节数
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * 格式化耗时
     */
    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> String.format("%.2fs", ms / 1000.0)
            else -> String.format("%.1fmin %.0fs", ms / 60000.0, (ms % 60000) / 1000.0)
        }
    }
}
