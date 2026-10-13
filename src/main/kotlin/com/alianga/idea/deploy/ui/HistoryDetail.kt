package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.HistoryRecord

/**
 * 历史详情区的纯逻辑：重新执行按钮的文案 / 目标动作、步骤链、备份字段。
 *
 * @author 郑明亮
 */
object HistoryDetail {

    /** 重新执行对应的动作：必须调用与记录类型匹配的动作，而不是一律重新部署 */
    enum class Rerun { DEPLOY, PUSH, PULL, NONE }

    fun rerun(r: HistoryRecord): Rerun = when (r.type) {
        HistoryRecord.OperationType.DEPLOY -> Rerun.DEPLOY
        HistoryRecord.OperationType.SYNC, HistoryRecord.OperationType.UPLOAD -> Rerun.PUSH
        HistoryRecord.OperationType.PULL -> Rerun.PULL
        else -> Rerun.NONE
    }

    /** 文案 key：失败 / 取消的记录统一为「重试」 */
    fun rerunLabelKey(r: HistoryRecord): String = when {
        rerun(r) == Rerun.NONE -> "history.detail.rerun.none"
        r.status != HistoryRecord.OperationStatus.SUCCESS -> "history.detail.rerun.retry"
        else -> "history.detail.rerun." + rerun(r).name.lowercase()
    }

    /** 备份字段：部署记录未备份显示「未启用备份」，其余类型显示 — */
    fun backupText(r: HistoryRecord, notEnabled: String): String = when {
        r.backupFilePath.isNotBlank() -> r.backupFilePath
        r.type == HistoryRecord.OperationType.DEPLOY -> notEnabled
        else -> "—"
    }

    enum class Step { PRE, BACKUP, UPLOAD, UNZIP, POST }
    enum class Mark(val symbol: String) { OK("✔"), FAIL("✖"), SKIP("–") }

    /**
     * 步骤链：未配置的步骤标「–」；成功记录已配置的步骤全「✔」；
     * 失败 / 取消的记录不知道断在哪一步，已配置步骤标「–」、上传标「✖」——不编造中间状态。
     */
    fun steps(r: HistoryRecord): List<Pair<Step, Mark>> {
        val configured = mapOf(
            Step.PRE to r.preCommand.isNotBlank(),
            Step.BACKUP to (r.backupDir.isNotBlank() || r.backupFilePath.isNotBlank()),
            Step.UPLOAD to true,
            Step.UNZIP to r.unzipDest.isNotBlank(),
            Step.POST to r.postCommand.isNotBlank()
        )
        val ok = r.status == HistoryRecord.OperationStatus.SUCCESS
        return Step.entries.map { s ->
            s to when {
                configured[s] != true -> Mark.SKIP
                ok -> Mark.OK
                s == Step.UPLOAD -> Mark.FAIL
                else -> Mark.SKIP
            }
        }
    }

    /** 变更文件：优先实际传输的文件，其次选中的远程路径 / 相对路径 */
    fun files(r: HistoryRecord): List<String> =
        r.transferredFiles.ifEmpty { r.remotePaths.ifEmpty { r.relativePaths } }
}
