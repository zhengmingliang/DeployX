package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.MappingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.0.8 新增：覆盖嵌套（父子目录）映射竞争中「手动选择映射」的核心算法。
 *
 * 场景（用户真实配置）：
 * - 子目录映射：local=`/opt/.../collection-server/target/lib` -> remote=`/app/TongWeb.../WEB-INF/lib`
 * - 父目录映射：local=`/opt/.../collection-server/target`     -> remote=`/app/cdb/collection`
 *
 * 选中 `target/lib`（或其下文件）时两条映射都匹配：
 * - 默认（preferredMappingId=null）取最深层级（子目录）映射 —— 保持 1.0.8 行为
 * - 传入父目录映射 ID 时优先使用父目录映射 —— 1.0.8 新增的手动选择能力
 *
 * 只调用 [MappingManager] 的伴生纯函数，不触发 Service 容器，可在普通 JVM 测试环境运行。
 */
class MappingChoiceTest {

    private fun mapping(
        id: String,
        localDir: String,
        remoteDir: String,
        serverId: String = "srv"
    ): MappingConfig =
        MappingConfig(id = id, name = id, localDir = localDir, serverId = serverId, remoteDir = remoteDir)

    private fun resolved(
        m: MappingConfig,
        relativePath: String,
        resolvedRemoteDir: String
    ): MappingManager.ResolvedMapping =
        MappingManager.ResolvedMapping(m, relativePath, resolvedRemoteDir)

    /** 用户场景：选中 .../target/lib（目录本身）时父子两条映射的解析结果 */
    private fun nestedScenario(): List<MappingManager.ResolvedMapping> {
        val child = mapping(
            "child",
            "/opt/workspace/icell/report/collection/collection-server/target/lib",
            "/app/TongWeb7.0.4.9_M1_Enterprise_Linux/deployment/ireport/WEB-INF/lib"
        )
        val parent = mapping(
            "parent",
            "/opt/workspace/icell/report/collection/collection-server/target",
            "/app/cdb/collection"
        )
        // resolvedRemoteDir = mapping.remoteDir + relativePath 的父级部分：
        // 子映射选中目录本身（relativePath=""）-> 远程根目录；
        // 父映射下选中目录相对路径为 "lib"（父级部分为空）-> 远程根目录 /app/cdb/collection，
        // 最终目的地由消费方按 remoteBaseDir + relativePath 拼出（/app/cdb/collection/lib）。
        return listOf(
            resolved(child, "", "/app/TongWeb7.0.4.9_M1_Enterprise_Linux/deployment/ireport/WEB-INF/lib"),
            resolved(parent, "lib", "/app/cdb/collection")
        )
    }

    @Test
    fun `default keeps deepest child mapping`() {
        val picked = MappingManager.pickResolvedByServer(nestedScenario(), "srv")
        assertEquals("child", picked?.mapping?.id)
    }

    @Test
    fun `preferred parent mapping wins over deepest child`() {
        val picked = MappingManager.pickResolvedByServer(nestedScenario(), "srv", preferredMappingId = "parent")
        assertEquals("parent", picked?.mapping?.id)
        // 走父目录映射时，选中目录的相对路径为 lib；消费方按 mapping.remoteDir + relativePath
        // 拼出最终目的地 /app/cdb/collection/lib
        assertEquals("lib", picked?.relativePath)
        assertEquals("/app/cdb/collection", picked?.resolvedRemoteDir)
    }

    @Test
    fun `unknown preferred id falls back to deepest`() {
        val picked = MappingManager.pickResolvedByServer(nestedScenario(), "srv", preferredMappingId = "not-exist")
        assertEquals("child", picked?.mapping?.id)
    }

    @Test
    fun `blank preferred id falls back to deepest`() {
        val picked = MappingManager.pickResolvedByServer(nestedScenario(), "srv", preferredMappingId = "  ")
        assertEquals("child", picked?.mapping?.id)
    }

    @Test
    fun `preferred id from another server is ignored`() {
        val other = mapping("other", "/data/other", "/r", serverId = "srv2")
        val list = nestedScenario() + resolved(other, "", "/r")
        val picked = MappingManager.pickResolvedByServer(list, "srv", preferredMappingId = "other")
        assertEquals("child", picked?.mapping?.id)
    }

    @Test
    fun `no candidate for server returns null`() {
        assertNull(MappingManager.pickResolvedByServer(nestedScenario(), "unknown-server"))
    }

    @Test
    fun `sortByPriority puts deepest first and keeps order on ties`() {
        val child = mapping("child", "/a/b/c/d", "/r1")
        val parent = mapping("parent", "/a/b", "/r2")
        val sibling = mapping("sibling", "/a/b", "/r3") // 与 parent 等长
        val sorted = MappingManager.sortByPriority(
            listOf(
                resolved(parent, "", "/r2"),
                resolved(child, "", "/r1"),
                resolved(sibling, "", "/r3")
            )
        )
        assertEquals(listOf("child", "parent", "sibling"), sorted.map { it.mapping.id })
    }

    @Test
    fun `formatMappingEntry contains name and paths, default marker extends text`() {
        val child = mapping("ireport-lib", "/opt/x/target/lib", "/app/WEB-INF/lib")
        val entry = MappingManager.formatMappingEntry(resolved(child, "", "/app/WEB-INF/lib"))
        assertTrue(entry.contains("ireport-lib"), "entry should contain mapping name: $entry")
        assertTrue(entry.contains("/opt/x/target/lib"), "entry should contain local dir: $entry")
        assertTrue(entry.contains("/app/WEB-INF/lib"), "entry should contain remote dir: $entry")
        val withDefault = MappingManager.formatMappingEntry(resolved(child, "", "/app/WEB-INF/lib"), isDefault = true)
        assertTrue(withDefault.length > entry.length, "default marker should extend the entry text")
    }

    @Test
    fun `formatMappingEntry falls back to local dir when name is blank`() {
        val noName = MappingConfig(id = "x", name = "", localDir = "/opt/x/target/lib", serverId = "srv", remoteDir = "/app")
        val entry = MappingManager.formatMappingEntry(resolved(noName, "", "/app"))
        assertTrue(entry.contains("/opt/x/target/lib"), "blank name should fall back to local dir: $entry")
    }
}
