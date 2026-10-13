package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.MappingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class MappingAutoFillTest {
    private val labels: (String) -> String = {
        mapOf("flow" to "流程：", "backup" to "备份", "upload" to "上传", "unzip" to "解压", "pre" to "前置命令",
            "post" to "后置命令", "preview" to "预览（dry-run）", "pull" to "拉取", "deploy" to "部署")[it]!!
    }

    @Test
    fun `无映射时全部清空`() = assertEquals(MappingAutoFill.EMPTY, MappingAutoFill.of(null, null))

    @Test
    fun `按映射带入远程路径与流程`() {
        val m = MappingConfig(name = "lib", localDir = "/p/lib", serverId = "233", remoteDir = "/app/lib",
            backupEnabled = true, backupDir = "/bak/", preCommand = "", postCommand = "restart")
        val f = MappingAutoFill.of(m, "/app/lib/sub")
        assertEquals("/app/lib/sub", f.remote)
        assertEquals(true, f.backupOn); assertEquals("/bak/", f.backupDir)
        assertEquals(false, f.preOn); assertEquals(true, f.postOn); assertEquals("restart", f.post)
    }

    @Test
    fun `解析目录为空时回退映射远程根`() {
        val m = MappingConfig(name = "x", localDir = "/a", serverId = "s", remoteDir = "/r")
        assertEquals("/r", MappingAutoFill.of(m, "").remote)
    }

    @Test
    fun `流程顺序：备份在前置命令之前`() =
        assertEquals("流程：备份 → 前置命令 → 上传 → 解压 → 后置命令", OperationSummary.flowText(true, true, "a", "b", labels))

    @Test
    fun `流程随模式变化`() {
        assertEquals("流程：上传", OperationSummary.flowText(true, true, "a", "b", labels, DeployMode.QUICK_PUSH))
        assertEquals("流程：预览（dry-run）", OperationSummary.flowText(true, true, "a", "b", labels, DeployMode.PREVIEW_PUSH))
        assertEquals("流程：拉取", OperationSummary.flowText(false, false, "", "", labels, DeployMode.PULL))
    }
}
