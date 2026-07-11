package com.alianga.idea.deploy.model

/**
 * 同步方向
 */
enum class SyncDirection {
    /** 本地上传到服务器（Push） */
    PUSH,
    /** 服务器下载到本地（Pull） */
    PULL
}

/**
 * 同步选项
 */
data class SyncOptions(
    val excludePatterns: List<String> = emptyList(),
    val dryRun: Boolean = false,
    val compress: Boolean = true,
    val deleteRemote: Boolean = false,
    /** 同步方向 */
    val direction: SyncDirection = SyncDirection.PUSH,
    /** PULL 时是否删除本地多余文件（与 deleteRemote 对应，仅用于 PULL） */
    val deleteLocal: Boolean = false
)
