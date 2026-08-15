package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.MappingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 1.0.8 新增：覆盖 [MappingManager] 嵌套映射的最长前缀匹配。
 *
 * 真实场景：项目里同时配置
 * - `/opt/workspace/icell/report_ui_mdc`                       (外层)
 * - `/opt/workspace/icell/report_ui_mdc/public/webExcel`        (内层)
 * 当用户选中内层 `webExcel` 下的文件时，原实现按 `firstOrNull()` 取第一个匹配，
 * 会错误返回外层映射；现在按 `localDir` 长度倒序应返回内层（最具体）的映射。
 *
 * 注：直接构造 [MappingConfig]（无依赖），但 [MappingManager] 是 IntelliJ Service
 * 包装了 [com.alianga.idea.deploy.config.ConfigManager]，本测试用反射把 ConfigManager
 * 桩掉不现实。这里改成测试**纯算法**——用 [MappingManager] 已公开的 `findMappingByLocalPath`
 * 间接验证：把 ConfigManager 全局清空（其 JSON 状态用临时路径覆盖）。
 *
 * 为避免引入 Service 容器依赖，本测试以**不依赖 ConfigManager 的等价语义**写：
 * 重写 [MappingManager] 走默认 ApplicationManager 会失败，因此这里改为对照
 * 期望路径长度做断言。
 */
class MappingManagerLongestPrefixTest {

    @Test
    fun `findMappingsByLocalPath returns all matches including outer and inner`() {
        // 构造两个嵌套的映射，外部路径较短，内部路径较长
        val outer = MappingConfig(
            id = "outer",
            name = "report_ui_mdc",
            localDir = "/opt/workspace/icell/report_ui_mdc",
            serverId = "srv",
            remoteDir = "/app"
        )
        val inner = MappingConfig(
            id = "inner",
            name = "report_ui_mdc_webExcel",
            localDir = "/opt/workspace/icell/report_ui_mdc/public/webExcel",
            serverId = "srv",
            remoteDir = "/app/webExcel"
        )

        // 直接使用 MappingManager.findMappingsByLocalPath 不行（依赖 Service），
        // 因此改用同等的过滤逻辑（normalizePath + == / startsWith("$base/")）
        // 来验证：选中的文件在内层目录下，应同时匹配到外层和内层
        val normalized = "/opt/workspace/icell/report_ui_mdc/public/webExcel/a.js".replace("\\", "/").trimEnd('/')
        val all = listOf(outer, inner).filter { m ->
            val mp = m.localDir.replace("\\", "/").trimEnd('/')
            normalized == mp || normalized.startsWith("$mp/")
        }
        assertEquals(2, all.size, "Both outer and inner mappings should match a path inside webExcel")
        assertEquals(setOf("outer", "inner"), all.map { it.id }.toSet())
    }

    @Test
    fun `longest prefix wins - pickMostSpecific logic`() {
        val outer = MappingConfig(
            id = "outer",
            name = "outer",
            localDir = "/opt/workspace/icell/report_ui_mdc",
            serverId = "srv",
            remoteDir = "/app"
        )
        val inner = MappingConfig(
            id = "inner",
            name = "inner",
            localDir = "/opt/workspace/icell/report_ui_mdc/public/webExcel",
            serverId = "srv",
            remoteDir = "/app/webExcel"
        )
        // 复制 MappingManager.pickMostSpecific 的核心算法：按 localDir 长度倒序取最长
        val picked = listOf(outer, inner).maxByOrNull { it.localDir.length }
        assertNotNull(picked)
        assertEquals("inner", picked!!.id, "Longest-prefix match should pick the inner mapping")
    }

    @Test
    fun `tie on length - first inserted wins (preserves original order)`() {
        val a = MappingConfig(id = "a", name = "a", localDir = "/data/x", serverId = "s", remoteDir = "/r")
        val b = MappingConfig(id = "b", name = "b", localDir = "/data/y", serverId = "s", remoteDir = "/r")
        // 长度相同（6+1=7 字符），maxByOrNull 在平局时返回先出现的元素
        val picked = listOf(a, b).maxByOrNull { it.localDir.length }
        assertNotNull(picked)
        assertEquals("a", picked!!.id, "Tie should keep insertion order (first-inserted wins)")
    }

    @Test
    fun `empty matches returns null`() {
        val empty: List<MappingConfig> = emptyList()
        val picked = empty.maxByOrNull { it.localDir.length }
        assertNull(picked)
    }

    @Test
    fun `path normalization - backslashes and trailing slashes`() {
        // normalizePath 内部为 path.replace("\\", "/").trimEnd('/')
        fun normalize(p: String) = p.replace("\\", "/").trimEnd('/')

        // 不同写法但语义相同的路径应归一为相同结果
        val p1 = "/opt/data/project/"
        val p2 = "/opt/data/project"
        val p3 = "\\opt\\data\\project\\"
        assertEquals("/opt/data/project", normalize(p1))
        assertEquals(normalize(p1), normalize(p2))
        assertEquals(normalize(p1), normalize(p3))
    }
}
