package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.HistoryRecord.OperationStatus
import com.alianga.idea.deploy.model.HistoryRecord.OperationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * @author 郑明亮
 */
class HistoryFilterTest {
    private val ok = HistoryRecord(id = "1", type = OperationType.DEPLOY, status = OperationStatus.SUCCESS,
        serverId = "233", serverName = "国开", sourcePath = "/p/lib", canRollback = true, backupFilePath = "/bak/a.tar.gz")
    private val noBackup = ok.copy(id = "2", canRollback = false, backupFilePath = "")
    private val failed = ok.copy(id = "3", status = OperationStatus.FAILED)
    private val sync = ok.copy(id = "4", type = OperationType.SYNC, serverId = "164", serverName = "164")
    private val all = listOf(ok, noBackup, failed, sync)

    @Test
    fun `可回滚判定与原因`() {
        assertNull(HistoryFilter.rollbackBlockReason(ok))
        assertEquals(HistoryFilter.BlockReason.NO_BACKUP, HistoryFilter.rollbackBlockReason(noBackup))
        assertEquals(HistoryFilter.BlockReason.FAILED, HistoryFilter.rollbackBlockReason(failed))
        assertEquals(HistoryFilter.BlockReason.NOT_DEPLOY, HistoryFilter.rollbackBlockReason(sync))
    }

    @Test
    fun `过滤与计数`() {
        assertEquals(listOf(ok), HistoryFilter.apply(all, HistoryFilter.Kind.ROLLBACKABLE, ""))
        assertEquals(listOf(failed), HistoryFilter.apply(all, HistoryFilter.Kind.FAILED, ""))
        val c = HistoryFilter.counts(all, "")
        assertEquals(4, c[HistoryFilter.Kind.ALL]); assertEquals(3, c[HistoryFilter.Kind.SUCCESS])
        assertEquals(1, c[HistoryFilter.Kind.FAILED]); assertEquals(1, c[HistoryFilter.Kind.ROLLBACKABLE])
    }

    @Test
    fun `搜索匹配服务器与动作`() {
        assertEquals(listOf(sync), HistoryFilter.apply(all, HistoryFilter.Kind.ALL, "164"))
        assertEquals(listOf(sync), HistoryFilter.apply(all, HistoryFilter.Kind.ALL, "SYNC"))
        assertEquals(1, HistoryFilter.counts(all, "164")[HistoryFilter.Kind.ALL])
    }

    @Test
    fun `按天分组`() {
        val today = LocalDate.of(2026, 10, 13)
        fun ts(d: LocalDate) = d.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(HistoryFilter.Day.TODAY, HistoryFilter.day(ts(today), today, ZoneOffset.UTC))
        assertEquals(HistoryFilter.Day.YESTERDAY, HistoryFilter.day(ts(today.minusDays(1)), today, ZoneOffset.UTC))
        assertEquals(HistoryFilter.Day.EARLIER, HistoryFilter.day(ts(today.minusDays(5)), today, ZoneOffset.UTC))
    }
}
