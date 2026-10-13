package com.alianga.idea.deploy.service

import java.io.File

/**
 * 单路径部署（操作页「部署」、历史重新执行单路径记录）的源 / 目标语义（1.1.1 修复 lib/lib 嵌套）。
 *
 * rsync 中 `/path/dir`（无结尾斜杠）表示把目录本身放到目标下，`/path/dir/` 表示只同步目录内容。
 * 操作页按映射带入远程路径时：选中映射根目录（如 local=/p/lib → remote=/app/lib）带入的是目录本身对应的远程路径，
 * 选中子目录（/p/lib/sub）带入的是其父目录对应的远程路径（/app/lib）。
 * 旧实现一律用无结尾斜杠的源路径，前者就会在远程建出 /app/lib/lib。
 *
 * 规则：本地是目录且远程路径末段与目录同名 → 同步目录内容；否则保持原语义（目录本身放到远程路径下）。
 * 部署、重新执行、备份源、报告都用同一规则，保证原操作与重新执行完全一致。
 *
 * @author 郑明亮
 */
object SingleDeployPaths {

    fun syncContents(localPath: String, isDirectory: Boolean, remotePath: String): Boolean {
        if (!isDirectory) return false
        val name = File(localPath.trimEnd('/', '\\')).name
        return name.isNotEmpty() && remotePath.trimEnd('/').substringAfterLast('/') == name
    }

    /** rsync 源参数（不含 Cygwin 转换） */
    fun rsyncSource(localPath: String, contents: Boolean): String =
        localPath.trimEnd('/') + if (contents) "/" else ""

    /** 报告 / 历史中的相对路径（相对 localPath 的父目录；同步内容时为空） */
    fun relativePaths(localPath: String, contents: Boolean): List<String> =
        if (contents) emptyList() else listOf(File(localPath.trimEnd('/')).name)

    /** 本次部署会覆盖的远程路径，未指定备份源时据此备份 */
    fun defaultBackupSource(localPath: String, remotePath: String, contents: Boolean): String =
        if (contents) remotePath.trimEnd('/') else "${remotePath.trimEnd('/')}/${File(localPath.trimEnd('/')).name}"
}
