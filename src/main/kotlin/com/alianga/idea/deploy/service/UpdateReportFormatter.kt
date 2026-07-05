package com.alianga.idea.deploy.service

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
        val status = if (report.success) "✅ 成功" else "❌ 失败"
        val operation = when (report.operationType) {
            "DEPLOY" -> "部署"
            "UPLOAD" -> "上传"
            "SYNC" -> "同步"
            else -> report.operationType
        }

        return buildString {
            // 标题
            appendLine("## 📦 文件更新报告")
            appendLine()

            // 摘要信息
            appendLine("| 项目 | 值 |")
            appendLine("|:---|:---|")
            appendLine("| 📅 时间 | $time |")
            appendLine("| ⚙️ 操作 | $operation |")
            appendLine("| 📊 状态 | $status |")
            appendLine("| 📁 分组数 | ${report.groups.size} |")

            // 汇总信息 - 使用实际传输的文件数量
            val totalTransferredFiles = report.groups.sumOf { it.transferredFiles.size }
            val totalSelectedFiles = report.groups.sumOf { it.remotePaths.size }
            val totalDuration = report.groups.sumOf { it.duration }
            val totalSize = report.groups.sumOf { it.totalSize }

            // 显示实际传输文件数（如果有）
            val displayFileCount = if (totalTransferredFiles > 0) totalTransferredFiles else totalSelectedFiles
            appendLine("| 📈 总文件数 | $displayFileCount |")
            appendLine("| ⏱️ 总耗时 | ${formatDuration(totalDuration)} |")
            appendLine("| 💾 传输大小 | ${formatBytes(totalSize)} |")
            appendLine()

            // 分组详情
            if (report.groups.isNotEmpty()) {
                appendLine("---")
                appendLine()

                report.groups.forEachIndexed { index, group ->
                    appendLine("### 分组 ${index + 1}")
                    appendLine()
                    appendLine("| 项目 | 值 |")
                    appendLine("|:---|:---|")
                    appendLine("| 🖥️ 服务器 | ${group.serverName.ifBlank { group.serverId }} |")
                    appendLine("| 🌐 地址 | `${group.serverAddress}` |")
                    appendLine("| 📊 状态 | ${if (group.success) "✅ 成功" else "❌ 失败"} |")
                    appendLine("| 📂 本地目录 | `${group.sourceBaseDir}` |")
                    appendLine("| 📂 远程目录 | `${group.remoteBaseDir}` |")

                    // 显示实际传输文件数
                    val fileCount = if (group.transferredFiles.isNotEmpty()) group.transferredFiles.size else group.remotePaths.size
                    appendLine("| 📄 文件数 | $fileCount |")
                    appendLine("| ⏱️ 耗时 | ${formatDuration(group.duration)} |")
                    if (group.totalSize > 0) {
                        appendLine("| 💾 传输大小 | ${formatBytes(group.totalSize)} |")
                    }

                    // 显示备份路径
                    if (!group.backupPath.isNullOrBlank()) {
                        appendLine("| 💾 备份位置 | `${group.backupPath}` |")
                    }
                    appendLine()

                    // 备份信息说明
                    if (!group.backupPath.isNullOrBlank()) {
                        appendLine("#### 📦 备份信息")
                        appendLine()
                        appendLine("备份文件已保存至: `${group.backupPath}`")
                        appendLine()
                    }

                    // 实际传输的文件列表（优先显示）
                    val displayFiles = if (group.transferredFiles.isNotEmpty()) {
                        group.transferredFiles
                    } else {
                        group.remotePaths
                    }

                    if (displayFiles.isNotEmpty()) {
                        if (group.transferredFiles.isNotEmpty()) {
                            appendLine("#### ✅ 实际更新的文件")
                        } else {
                            appendLine("#### 📄 上传文件")
                        }
                        appendLine()
                        appendLine("```text")
                        displayFiles.forEach { appendLine(it) }
                        appendLine("```")
                        appendLine()
                    }
                }
            }

            // 备注
            appendLine("---")
            appendLine()
            appendLine("> 💡 提示：目录同步时，rsync 仅传输变更的文件。报告生成时间: $time")
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
