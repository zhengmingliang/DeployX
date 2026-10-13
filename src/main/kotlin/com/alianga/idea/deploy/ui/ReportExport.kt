package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord
import java.io.File

/**
 * 历史报告导出：与「复制报告」使用同一份 reportText，按 UTF-8 完整写出。
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
}
