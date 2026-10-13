package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.model.DeployItem
import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.UploadItem
import java.io.File

/**
 * 历史「重新执行」的执行计划（1.1.1 修复：重新部署嵌套出 lib/lib）。
 *
 * 右键批量部署 / 推送的记录中 sourcePath、targetPath 是映射根目录，relativePaths 相对映射根；
 * 旧实现一律用 DeployRequest(localPath=映射根, remotePath=映射根) 走单路径部署，
 * rsync 源路径没有结尾斜杠，于是在远程映射根下又建了一层同名目录。
 * 现在批量记录重建与原操作相同的 DeployItem / UploadItem，交给 deployBatch / uploadBatch。
 *
 * @author 郑明亮
 */
object RedeployPlan {

    /** 是否为批量（映射根 + 相对路径）记录 */
    fun isBatch(r: HistoryRecord): Boolean = when (r.origin) {
        "batch" -> true
        "single" -> false
        // 旧记录：单路径部署的 relativePaths 恰为 [源路径的名字]
        else -> r.relativePaths != listOf(File(r.sourcePath).name)
    }

    private fun rels(r: HistoryRecord): List<String> =
        r.relativePaths.map { it.trim('/') }.filter { it.isNotBlank() }.ifEmpty { listOf("") }

    private fun local(r: HistoryRecord, rel: String) =
        if (rel.isEmpty()) r.sourcePath.trimEnd('/') else r.sourcePath.trimEnd('/') + "/" + rel

    fun deployItems(
        r: HistoryRecord, mappingId: String = "", backupSource: String? = null,
        isDir: (String) -> Boolean = { File(it).isDirectory }
    ): List<DeployItem> = rels(r).map { rel ->
        val lp = local(r, rel)
        DeployItem(
            localPath = lp, isDirectory = isDir(lp), serverId = r.serverId, mappingId = mappingId,
            sourceBaseDir = r.sourcePath.trimEnd('/'), remoteBaseDir = r.targetPath.trimEnd('/'), relativePath = rel,
            excludePatterns = r.excludePatterns, backupDir = r.backupDir.ifBlank { null }, backupSource = backupSource,
            unzipDest = r.unzipDest.ifBlank { null }, preCommand = r.preCommand.ifBlank { null }, postCommand = r.postCommand.ifBlank { null }
        )
    }

    fun uploadItems(
        r: HistoryRecord, mappingId: String = "",
        isDir: (String) -> Boolean = { File(it).isDirectory }
    ): List<UploadItem> = rels(r).map { rel ->
        val lp = local(r, rel)
        UploadItem(
            localPath = lp, isDirectory = isDir(lp), serverId = r.serverId, mappingId = mappingId,
            sourceBaseDir = r.sourcePath.trimEnd('/'), remoteBaseDir = r.targetPath.trimEnd('/'), relativePath = rel,
            excludePatterns = r.excludePatterns, preCommand = r.preCommand.ifBlank { null }, postCommand = r.postCommand.ifBlank { null }
        )
    }

    /** rsync 实际使用的源相对路径（与 DeployService 批量分支同一规则），用于测试比对 */
    fun rsyncRelative(isDirectory: Boolean, relativePath: String): String =
        if (isDirectory) relativePath.trimEnd('/') + "/" else relativePath.trim('/')
}
