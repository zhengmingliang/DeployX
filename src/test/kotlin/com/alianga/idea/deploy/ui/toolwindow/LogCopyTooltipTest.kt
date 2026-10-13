package com.alianga.idea.deploy.ui.toolwindow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStreamReader
import java.util.Properties

/** #26：日志子页签复制图标的 tooltip 按页签区分，「全部」页签不提「此服务器」。 */
class LogCopyTooltipTest {
    private fun load(name: String) = Properties().apply {
        File("src/main/resources/messages/$name").inputStream().use { load(InputStreamReader(it, Charsets.UTF_8)) }
    }

    @Test
    fun keyDependsOnTab() {
        assertEquals("toolwindow.action.copyLog.tooltip.allTab", copyTabTooltipKey(null))
        assertEquals("toolwindow.action.copyLog.tooltip.allTab", copyTabTooltipKey(""))
        assertEquals("toolwindow.action.copyLog.tooltip", copyTabTooltipKey("local"))
    }

    @Test
    fun bundlesHaveDistinctTexts() {
        for (b in listOf("DeployXBundle.properties", "DeployXBundle_zh_CN.properties")) {
            val p = load(b)
            val tab = p.getProperty("toolwindow.action.copyLog.tooltip")
            val all = p.getProperty("toolwindow.action.copyLog.tooltip.allTab")
            val every = p.getProperty("toolwindow.action.copyAllLog.tooltip")
            assertTrue(!tab.isNullOrBlank() && !all.isNullOrBlank() && !every.isNullOrBlank(), b)
            assertFalse(all.contains("此服务器") || all.contains("this server"), b)
            assertEquals(3, setOf(tab, all, every).size, b)
        }
    }
}
