package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.MappingConfig

/**
 * 操作页「按映射带入」的字段值（原型 pickServer / applyMap，纯函数，便于单测）。
 * 映射为 null（该服务器没有匹配映射）时返回全部清空的值。
 *
 * @author 郑明亮
 */
object MappingAutoFill {

    data class Fields(
        val remote: String,
        val backupOn: Boolean, val backupDir: String,
        val unzipOn: Boolean, val unzipDir: String,
        val preOn: Boolean, val pre: String,
        val postOn: Boolean, val post: String
    )

    val EMPTY = Fields("", false, "", false, "", false, "", false, "")

    fun of(mapping: MappingConfig?, resolvedRemoteDir: String?): Fields {
        mapping ?: return EMPTY
        return Fields(
            remote = resolvedRemoteDir?.takeIf { it.isNotBlank() } ?: mapping.remoteDir,
            backupOn = mapping.backupEnabled, backupDir = mapping.backupDir,
            unzipOn = mapping.unzipEnabled, unzipDir = mapping.unzipDest,
            preOn = mapping.effectivePreCommandEnabled, pre = mapping.preCommand,
            postOn = mapping.effectivePostCommandEnabled, post = mapping.postCommand
        )
    }
}
