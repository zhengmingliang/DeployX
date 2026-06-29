package com.alianga.idea.filesync.service

import com.alianga.idea.filesync.model.UpdateReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 更新报告 Markdown 格式化器。
 */
object UpdateReportFormatter {

    fun format(report: UpdateReport): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(report.timestamp))
        return buildString {
            appendLine("# File Sync Update Report")
            appendLine()
            appendLine("- Time: $time")
            appendLine("- Operation: ${report.operationType}")
            appendLine("- Status: ${if (report.success) "SUCCESS" else "FAILED"}")
            appendLine("- Groups: ${report.groups.size}")
            appendLine()
            appendLine("> Note: This report lists selected upload/deploy paths resolved on the server. For directories, rsync may transfer only changed child files.")
            appendLine()

            report.groups.forEachIndexed { index, group ->
                appendLine("## Group ${index + 1}")
                appendLine()
                appendLine("- Server: ${group.serverName.ifBlank { group.serverId }}")
                appendLine("- Address: ${group.serverAddress}")
                appendLine("- Status: ${if (group.success) "SUCCESS" else "FAILED"}")
                appendLine("- Local base: `${group.sourceBaseDir}`")
                appendLine("- Remote base: `${group.remoteBaseDir}`")
                appendLine("- Paths: ${group.remotePaths.size}")
                appendLine("- Duration: ${group.duration} ms")
                appendLine("- Total bytes: ${group.totalSize}")
                appendLine()

                appendLine("### Server paths")
                appendLine()
                appendLine("```text")
                group.remotePaths.forEach { appendLine(it) }
                appendLine("```")
                appendLine()

                appendLine("### Relative paths")
                appendLine()
                appendLine("```text")
                group.relativePaths.forEach { appendLine(it) }
                appendLine("```")
                appendLine()

                appendLine("### Local selections")
                appendLine()
                appendLine("```text")
                group.selectedLocalPaths.forEach { appendLine(it) }
                appendLine("```")
                appendLine()
            }
        }
    }
}
