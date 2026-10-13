package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.HistoryRecord

/**
 * 回滚命令与选项（纯函数）。确认框里展示的命令原文与 [RollbackService] 实际执行的命令同源，
 * 保证「看到的就是要执行的」。
 *
 * @author 郑明亮
 */
object RollbackCommands {

    /** 回滚选项：回滚前先备份当前状态（推荐，默认开）、回滚后执行上传后命令（默认关） */
    data class Options(val backupFirst: Boolean = false, val runPostCommand: Boolean = false)

    /** Shell 单引号转义（与原 RollbackService.shellEscape 一致） */
    fun esc(path: String): String = path.replace("'", "'\\''")

    fun restore(backupFile: String, targetDir: String): String =
        "tar -xzf '${esc(backupFile)}' -C '${esc(targetDir)}' 2>&1"

    /** 备份当前状态：放在原备份文件同目录，文件名 rollback-pre-<时间戳>.tar.gz */
    fun backupCurrentPath(backupFile: String, timestamp: String): String {
        val dir = backupFile.substringBeforeLast('/', "").ifEmpty { "/tmp" }
        return "$dir/rollback-pre-$timestamp.tar.gz"
    }

    fun backupCurrent(targetDir: String, archive: String): String =
        "tar -czf '${esc(archive)}' -C '${esc(targetDir)}' . 2>&1"

    fun postCommand(targetDir: String, post: String): String = "cd '${esc(targetDir)}' && ${post.trim()}"

    /** 将要执行的命令列表（确认框预览用） */
    fun preview(record: HistoryRecord, options: Options, timestamp: String): List<String> = buildList {
        if (options.backupFirst) add(backupCurrent(record.targetPath, backupCurrentPath(record.backupFilePath, timestamp)))
        add(restore(record.backupFilePath, record.targetPath))
        if (options.runPostCommand && record.postCommand.isNotBlank()) add(postCommand(record.targetPath, record.postCommand))
    }

    /** 此后在同一服务器同一目标上又有多少次成功的部署 / 推送（其结果也会被覆盖） */
    fun newerSuccessfulCount(record: HistoryRecord, all: List<HistoryRecord>): Int = all.count {
        it.id != record.id && it.timestamp > record.timestamp && it.serverId == record.serverId &&
            it.targetPath == record.targetPath && it.status == HistoryRecord.OperationStatus.SUCCESS &&
            it.type in setOf(HistoryRecord.OperationType.DEPLOY, HistoryRecord.OperationType.SYNC, HistoryRecord.OperationType.UPLOAD)
    }
}
