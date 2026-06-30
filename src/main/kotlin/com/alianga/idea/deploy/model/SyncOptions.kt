package com.alianga.idea.deploy.model

/**
 * 同步选项
 */
data class SyncOptions(
    val excludePatterns: List<String> = emptyList(),
    val dryRun: Boolean = false,
    val compress: Boolean = true,
    val deleteRemote: Boolean = false
)
