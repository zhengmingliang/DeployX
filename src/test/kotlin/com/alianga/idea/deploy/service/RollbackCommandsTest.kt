package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.HistoryRecord.OperationStatus
import com.alianga.idea.deploy.model.HistoryRecord.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * @author 郑明亮
 */
class RollbackCommandsTest {
    private val rec = HistoryRecord(id = "1", timestamp = 1000, type = OperationType.DEPLOY, serverId = "s",
        targetPath = "/app/lib", backupFilePath = "/bak/a.tar.gz", postCommand = "sh restart.sh", canRollback = true)

    @Test fun restoreEscapesQuotes() =
        assertEquals("tar -xzf '/b/it'\\''s.tgz' -C '/t' 2>&1", RollbackCommands.restore("/b/it's.tgz", "/t"))

    @Test fun backupPathSameDir() {
        assertEquals("/bak/rollback-pre-X.tar.gz", RollbackCommands.backupCurrentPath("/bak/a.tar.gz", "X"))
        assertEquals("/tmp/rollback-pre-X.tar.gz", RollbackCommands.backupCurrentPath("a.tar.gz", "X"))
    }

    @Test fun previewDefaultOnlyRestore() =
        assertEquals(listOf(RollbackCommands.restore("/bak/a.tar.gz", "/app/lib")),
            RollbackCommands.preview(rec, RollbackCommands.Options(), "X"))

    @Test fun previewAllOptions() {
        val p = RollbackCommands.preview(rec, RollbackCommands.Options(backupFirst = true, runPostCommand = true), "X")
        assertEquals(3, p.size)
        assertEquals("tar -czf '/bak/rollback-pre-X.tar.gz' -C '/app/lib' . 2>&1", p[0])
        assertEquals("cd '/app/lib' && sh restart.sh", p[2])
    }

    @Test fun postSkippedWhenBlank() =
        assertEquals(1, RollbackCommands.preview(rec.copy(postCommand = ""), RollbackCommands.Options(runPostCommand = true), "X").size)

    @Test fun newerCount() {
        val all = listOf(rec,
            rec.copy(id = "2", timestamp = 2000),
            rec.copy(id = "3", timestamp = 3000, type = OperationType.SYNC),
            rec.copy(id = "4", timestamp = 4000, status = OperationStatus.FAILED),
            rec.copy(id = "5", timestamp = 5000, targetPath = "/other"),
            rec.copy(id = "6", timestamp = 500),
            rec.copy(id = "7", timestamp = 6000, type = OperationType.PULL))
        assertEquals(2, RollbackCommands.newerSuccessfulCount(rec, all))
    }
}

class RollbackHistoryRecordTest {
    private val rec = HistoryRecord(id = "1", type = OperationType.DEPLOY, serverId = "s", serverName = "S",
        targetPath = "/app/lib", backupFilePath = "/bak/a.tar.gz", postCommand = "sh restart.sh")

    @Test fun rollbackRecordAndSteps() {
        val h = RollbackCommands.historyRecord(rec, OperationStatus.SUCCESS, 10, "/bak/pre.tgz", true, listOf("a"), "rep")
        assertEquals(OperationType.ROLLBACK, h.type)
        assertEquals("/bak/a.tar.gz", h.sourcePath); assertEquals("/app/lib", h.targetPath); assertEquals("sh restart.sh", h.postCommand)
        val steps = com.alianga.idea.deploy.ui.HistoryDetail.steps(h)
        assertEquals(listOf("ROLLBACK_BACKUP", "RESTORE", "POST"), steps.map { it.first.name })
        assertEquals(listOf("✔", "✔", "✔"), steps.map { it.second.symbol })
        assertEquals(com.alianga.idea.deploy.ui.HistoryDetail.Rerun.NONE, com.alianga.idea.deploy.ui.HistoryDetail.rerun(h))
        val f = RollbackCommands.historyRecord(rec, OperationStatus.FAILED, 10, null, false, emptyList(), "")
        assertEquals(listOf("–", "✖", "–"), com.alianga.idea.deploy.ui.HistoryDetail.steps(f).map { it.second.symbol })
    }
}
