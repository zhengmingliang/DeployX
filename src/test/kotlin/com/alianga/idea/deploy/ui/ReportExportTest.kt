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
