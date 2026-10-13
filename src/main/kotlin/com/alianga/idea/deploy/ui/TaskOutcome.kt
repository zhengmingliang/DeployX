package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.DeployResult
import com.alianga.idea.deploy.model.SyncResult

/**
 * 任务结果判定（review #28）：由任务回调返回的结果对象给出明确的成功标志，
 * 不再靠进度文字里是否含「失败 / fail / error」判断（英文界面或路径含 error 时会误判）。
 *
 * @author 郑明亮
 */
object TaskOutcome {

    /** 返回 true=有失败，false=全部成功，null=结果类型未知（无结果，如任务被终止） */
    fun failed(result: Any?): Boolean? = when (result) {
        is DeployResult -> !result.success
        is SyncResult -> !result.success
        is List<*> -> if (result.isEmpty()) null else result.map { failed(it) }.let { l ->
            if (l.any { it == null }) null else l.any { it == true }
        }
        else -> null
    }
}
