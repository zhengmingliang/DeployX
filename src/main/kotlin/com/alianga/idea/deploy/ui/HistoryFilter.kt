package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 历史页过滤与分组（实施方案 3.7「历史」，纯函数，便于单测）。
 *
 * @author 郑明亮
 */
object HistoryFilter {

    enum class Kind { ALL, SUCCESS, FAILED, ROLLBACKABLE }

    enum class Day { TODAY, YESTERDAY, EARLIER }

    /**
     * 可回滚：成功的部署，且当次启用了备份并记录了备份文件路径。
     * 旧版记录没有 backupFilePath，一律视为不可回滚，不猜测。
     */
    fun rollbackable(r: HistoryRecord): Boolean = rollbackBlockReason(r) == null

    /** 不可回滚的原因（用文字写明，不只靠置灰 / tooltip）；可回滚返回 null */
    enum class BlockReason { NOT_DEPLOY, FAILED, NO_BACKUP }

    fun rollbackBlockReason(r: HistoryRecord): BlockReason? = when {
        r.type != HistoryRecord.OperationType.DEPLOY -> BlockReason.NOT_DEPLOY
        r.status != HistoryRecord.OperationStatus.SUCCESS -> BlockReason.FAILED
        !r.canRollback || r.backupFilePath.isBlank() -> BlockReason.NO_BACKUP
        else -> null
    }

    fun matches(r: HistoryRecord, kind: Kind): Boolean = when (kind) {
        Kind.ALL -> true
        Kind.SUCCESS -> r.status == HistoryRecord.OperationStatus.SUCCESS
        Kind.FAILED -> r.status == HistoryRecord.OperationStatus.FAILED
        Kind.ROLLBACKABLE -> rollbackable(r)
    }

    /** 搜索：动作 / 服务器（id、名称、地址）/ 路径，不区分大小写 */
    fun matchesQuery(r: HistoryRecord, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return listOf(r.type.value, r.serverId, r.serverName, r.serverAddress, r.sourcePath, r.targetPath)
            .any { it.lowercase().contains(q) }
    }

    fun apply(records: List<HistoryRecord>, kind: Kind, query: String): List<HistoryRecord> =
        records.filter { matches(it, kind) && matchesQuery(it, query) }

    /** 各过滤胶囊的计数（受搜索词影响） */
    fun counts(records: List<HistoryRecord>, query: String): Map<Kind, Int> {
        val base = records.filter { matchesQuery(it, query) }
        return Kind.entries.associateWith { k -> base.count { matches(it, k) } }
    }

    fun day(timestamp: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Day {
        val d = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return when {
            d == today -> Day.TODAY
            d == today.minusDays(1) -> Day.YESTERDAY
            else -> Day.EARLIER
        }
    }
}
