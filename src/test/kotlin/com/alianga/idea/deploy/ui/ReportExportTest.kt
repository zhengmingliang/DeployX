package com.alianga.idea.deploy.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * @author 郑明亮
 */
class ReportExportTest {
    @Test fun writesFullUtf8Report() {
        val text = "# 更新报告\n服务器：本机测试 (box@127.0.0.1:2222)\n" + "文件 ".repeat(2000)
        val f = Files.createTempDirectory("dx").resolve("sub/report.md").toFile()
        ReportExport.write(f, text)
        assertEquals(text, f.readText(Charsets.UTF_8))
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), f.length())
    }
}

class ReportReconcileTest {
    private val old = """
        || 📊 Status | ✅ Success |
        || 📂 Local directory | `/workspace/dx-test-project` |
        || 📂 Remote directory | `/workspace/dx-remote/app/lib` |
        || 💾 Backup location | `/workspace/dx-remote/backup` |
        |
        |#### 📦 Backup information
        |
        |Backup file saved to: `/workspace/dx-remote/backup`
        |
        |#### ✅ Actually updated files
        """.trimMargin()

    private fun rec(backupFile: String) = com.alianga.idea.deploy.model.HistoryRecord(
        type = com.alianga.idea.deploy.model.HistoryRecord.OperationType.DEPLOY,
        sourcePath = "/workspace/dx-test-project/lib", targetPath = "/workspace/dx-remote/app/lib",
        backupDir = "/workspace/dx-remote/backup", backupFilePath = backupFile
    )

    @Test fun usesRecordLocalPathAndDropsBackupWhenNone() {
        val out = ReportExport.reconcile(rec(""), old)
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("| 📂 Local directory | `/workspace/dx-test-project/lib` |"))
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("Backup"))
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("Remote directory"))
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("Actually updated files"))
    }

    @Test fun keepsBackupWhenRecordHasBackupFile() {
        val out = ReportExport.reconcile(rec("/workspace/dx-remote/backup/lib_bak.tar.gz"), old)
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("Backup file saved to"))
    }
}
