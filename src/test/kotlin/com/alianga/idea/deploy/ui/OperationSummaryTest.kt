package com.alianga.idea.deploy.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class OperationSummaryTest {
    private val labels: (String) -> String = {
        mapOf("deploy" to "部署", "flow" to "流程：", "backup" to "备份", "upload" to "上传",
            "unzip" to "解压", "pre" to "前置命令", "post" to "后置命令")[it]!!
    }

    @Test
    fun `完整输入时就绪且摘要两行`() {
        val r = OperationSummary.build("/p/target/lib/", "233 · 国开", "/app/lib/", false, false, "", "restart", labels)
        assertTrue(r.ready)
        assertEquals("部署 lib → 233 · 国开 /app/lib/", r.headline)
        assertEquals("流程：上传 → 后置命令", r.flow)
    }

    @Test
    fun `缺项按服务器-本地-远程顺序列出`() {
        val r = OperationSummary.build(" ", null, "", false, false, "", "", labels)
        assertFalse(r.ready)
        assertEquals(listOf(OperationSummary.Missing.SERVER, OperationSummary.Missing.LOCAL, OperationSummary.Missing.REMOTE), r.missing)
    }

    @Test
    fun `流程包含全部启用步骤`() =
        assertEquals("流程：前置命令 → 备份 → 上传 → 解压 → 后置命令", OperationSummary.flowText(true, true, "a", "b", labels))

    @Test
    fun `路径末段`() {
        assertEquals("dist.zip", OperationSummary.shortName("/build/dist.zip"))
        assertEquals("lib", OperationSummary.shortName("C:\\\\x\\\\lib\\\\"))
        assertEquals("", OperationSummary.shortName(""))
    }

    @Test
    fun `模式解析：未知值或方向不符回落默认`() {
        assertEquals(DeployMode.QUICK_PUSH, DeployMode.parse("QUICK_PUSH", DeployMode.DEPLOY))
        assertEquals(DeployMode.DEPLOY, DeployMode.parse("PULL", DeployMode.DEPLOY))
        assertEquals(DeployMode.PULL, DeployMode.parse(null, DeployMode.PULL))
        assertEquals(DeployMode.PREVIEW_PULL, DeployMode.parse("PREVIEW_PULL", DeployMode.PULL))
    }
}
