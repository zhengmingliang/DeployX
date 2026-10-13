package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.HistoryRecord.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 重新执行必须重建与原批量部署相同的路径：映射根整体部署时 rsync 源为「映射根/」（带结尾斜杠），
 * 远程仍为映射根，不会嵌套出 lib/lib。
 *
 * @author 郑明亮
 */
class RedeployPlanTest {
    private val rootDeploy = HistoryRecord(type = OperationType.DEPLOY, origin = "batch", sourcePath = "/p/lib", serverId = "s",
        targetPath = "/r/app/lib", relativePaths = emptyList(), backupDir = "/bak", preCommand = "echo a")

    @Test fun mappingRootKeepsTrailingSlash() {
        val items = RedeployPlan.deployItems(rootDeploy, "m1") { true }
        assertEquals(1, items.size)
        val i = items[0]
        assertEquals("/p/lib", i.sourceBaseDir); assertEquals("/r/app/lib", i.remoteBaseDir)
        assertEquals("", i.relativePath); assertEquals("/p/lib", i.localPath)
        assertEquals("/", RedeployPlan.rsyncRelative(i.isDirectory, i.relativePath))
        assertEquals("/bak", i.backupDir); assertEquals("echo a", i.preCommand); assertEquals("m1", i.mappingId)
    }

    @Test fun childItemsRebuilt() {
        val r = rootDeploy.copy(relativePaths = listOf("sub", "core.jar.txt"))
        val items = RedeployPlan.deployItems(r) { it.endsWith("sub") }
        assertEquals(listOf("/p/lib/sub", "/p/lib/core.jar.txt"), items.map { it.localPath })
        assertEquals(listOf("sub/", "core.jar.txt"), items.map { RedeployPlan.rsyncRelative(it.isDirectory, it.relativePath) })
        assertTrue(items.all { it.remoteBaseDir == "/r/app/lib" })
    }

    @Test fun uploadItems() {
        val r = rootDeploy.copy(type = OperationType.UPLOAD, relativePaths = listOf("a.txt"))
        val u = RedeployPlan.uploadItems(r) { false }.single()
        assertEquals("/p/lib/a.txt", u.localPath); assertEquals("a.txt", u.relativePath); assertEquals("/r/app/lib", u.remoteBaseDir)
    }

    @Test fun batchDetection() {
        assertTrue(RedeployPlan.isBatch(rootDeploy))
        assertFalse(RedeployPlan.isBatch(rootDeploy.copy(origin = "single")))
        // 旧记录：单路径部署 relativePaths = [源路径名]
        assertFalse(RedeployPlan.isBatch(rootDeploy.copy(origin = "", relativePaths = listOf("lib"), targetPath = "/r/app")))
        assertTrue(RedeployPlan.isBatch(rootDeploy.copy(origin = "", relativePaths = emptyList())))
    }
}
