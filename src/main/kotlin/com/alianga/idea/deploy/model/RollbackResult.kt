package com.alianga.idea.deploy.model

/**
 * 回滚执行结果
 */
data class RollbackResult(
    /** 是否成功 */
    val success: Boolean,

    /** 回滚记录 ID */
    val recordId: String = "",

    /** 恢复的文件列表 */
    val rolledBackFiles: List<String> = emptyList(),

    /** 执行耗时（毫秒） */
    val duration: Long = 0,

    /** 错误信息（失败时） */
    val error: String? = null,

    /** 执行日志 */
    val logs: List<String> = emptyList(),

    /** 回滚报告文本 */
    val reportText: String = ""
)
