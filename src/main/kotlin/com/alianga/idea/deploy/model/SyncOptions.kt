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
    val deleteLocal: Boolean = false,
    /** PUSH 且本地为目录时只同步目录内容（源路径加结尾斜杠），见 SingleDeployPaths */
    val contentsOnly: Boolean = false
)
