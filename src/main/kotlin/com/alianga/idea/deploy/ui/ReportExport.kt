package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord
import java.io.File

/**
 * 历史报告导出 / 复制：与记录本身保持一致，按 UTF-8 完整写出。
 *
 * 1.1.1：旧版本生成的单路径部署报告把「本地目录」写成源路径的父目录（常常是项目根），
 * 且只要填了备份目录就写「备份位置 / 备份文件已保存至」，哪怕实际没有生成备份。
 * 导出 / 复制前按记录修正：单组报告的本地目录改为记录的本地路径；记录没有备份文件时去掉备份行与备份信息段。
 *
 * @author 郑明亮
 */
object ReportExport {
    fun defaultFileName(r: HistoryRecord): String =
        "deployx-report-${r.formattedDate.replace("[ :/]".toRegex(), "-")}.md"

    fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private val LOCAL_DIR_ROW = listOf("| \uD83D\uDCC2 Local directory |", "| \uD83D\uDCC2 本地目录 |")
    private val BACKUP_ROW = listOf("| \uD83D\uDCBE Backup location |", "| \uD83D\uDCBE 备份位置 |")
    private val BACKUP_TITLE = listOf("#### \uD83D\uDCE6 Backup information", "#### \uD83D\uDCE6 备份信息")
    private val BACKUP_PATH = listOf("Backup file saved to:", "备份文件已保存至:")

    /** 按记录修正报告文本（rollback / script 等记录原样返回） */
    fun reconcile(r: HistoryRecord, text: String): String {
        if (r.type != HistoryRecord.OperationType.DEPLOY && r.type != HistoryRecord.OperationType.UPLOAD) return text
        var lines = text.lines()
        val localRows = lines.count { l -> LOCAL_DIR_ROW.any { l.startsWith(it) } }
        if (localRows == 1 && r.sourcePath.isNotBlank()) {
            lines = lines.map { l ->
                val prefix = LOCAL_DIR_ROW.firstOrNull { l.startsWith(it) }
                if (prefix != null) "$prefix `${r.sourcePath}` |" else l
            }
        }
        if (r.backupFilePath.isBlank()) {
            val out = mutableListOf<String>()
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                when {
                    BACKUP_ROW.any { l.startsWith(it) } -> i++
                    BACKUP_TITLE.any { l.trim() == it } -> {
                        // 标题、空行、路径行、空行
                        i++
                        while (i < lines.size && (lines[i].isBlank() || BACKUP_PATH.any { lines[i].startsWith(it) })) i++
                    }
                    else -> { out += l; i++ }
                }
            }
            lines = out
        }
        return lines.joinToString("\n")
    }
}
