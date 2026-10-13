package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.HistoryRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * 单路径部署源 / 目标语义：操作页部署与历史重新执行同一规则，不再嵌套 lib/lib。
 *
 * @author 郑明亮
 */
class SingleDeployPathsTest {

    /** 18:11:30 那条操作页部署记录 */
    private fun record(src: String, dst: String) = HistoryRecord(
        origin = "single", type = HistoryRecord.OperationType.DEPLOY,
        sourcePath = src, targetPath = dst, serverId = "local", relativePaths = listOf("lib")
    )

    @Test fun mappingRootDirSyncsContents() {
        assertTrue(SingleDeployPaths.syncContents("/workspace/dx-test-project/lib", true, "/workspace/dx-remote/app/lib"))
        assertTrue(SingleDeployPaths.syncContents("/p/lib/", true, "/app/lib/"))
        assertEquals("/p/lib/", SingleDeployPaths.rsyncSource("/p/lib", true))
        assertEquals(emptyList<String>(), SingleDeployPaths.relativePaths("/p/lib", true))
        assertEquals("/app/lib", SingleDeployPaths.defaultBackupSource("/p/lib", "/app/lib/", true))
    }

    @Test fun subDirIntoParentKeepsOldSemantics() {
        // 子目录 /p/lib/sub 带入父目录 /app/lib：目录本身放到其下 → /app/lib/sub
        assertFalse(SingleDeployPaths.syncContents("/p/lib/sub", true, "/app/lib"))
        assertEquals("/p/lib/sub", SingleDeployPaths.rsyncSource("/p/lib/sub/", false))
        assertEquals(listOf("sub"), SingleDeployPaths.relativePaths("/p/lib/sub", false))
        assertEquals("/app/lib/sub", SingleDeployPaths.defaultBackupSource("/p/lib/sub", "/app/lib", false))
        // 文件永远不加斜杠
        assertFalse(SingleDeployPaths.syncContents("/p/lib.jar", false, "/app/lib.jar"))
    }

    @Test fun rerunUsesSameRequestAsOriginal() {
        val req = record("/p/lib", "/app/lib").toDeployRequest()
        assertEquals("/p/lib", req.localPath)
        assertEquals("/app/lib", req.remotePath)
    }

    /** 端到端：用真实 rsync 按插件生成的源 / 目标参数执行两次（原操作 + 重新执行），远程不出现 lib/lib */
    @Test fun realRsyncOriginalAndRerunDoNotNest() {
        val rsync = listOf("/usr/bin/rsync", "/bin/rsync", "/usr/local/bin/rsync").firstOrNull { File(it).canExecute() }
        assumeTrue(rsync != null, "rsync not installed")
        val root = Files.createTempDirectory("dx-e2e").toFile()
        val lib = File(root, "project/lib").apply { mkdirs() }
        File(lib, "core.jar.txt").writeText("core")
        File(lib, "sub").mkdirs(); File(lib, "sub/util.txt").writeText("util")
        val remote = File(root, "remote/app/lib").apply { mkdirs() }

        fun deploy(localPath: String, remotePath: String) {
            val contents = SingleDeployPaths.syncContents(localPath, File(localPath).isDirectory, remotePath)
            val p = ProcessBuilder(rsync, "-az", SingleDeployPaths.rsyncSource(localPath, contents), "$remotePath/")
                .redirectErrorStream(true).start()
            assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        }
        deploy(lib.path, remote.path)                      // 操作页原操作
        val req = record(lib.path, remote.path).toDeployRequest()
        deploy(req.localPath, req.remotePath)              // 历史重新执行
        assertTrue(File(remote, "core.jar.txt").isFile)
        assertTrue(File(remote, "sub/util.txt").isFile)
        assertFalse(File(remote, "lib").exists(), "nested lib/lib created")
        root.deleteRecursively()
    }
}
