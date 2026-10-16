package com.alianga.idea.deploy.service

import java.nio.file.Paths

/**
 * 远程路径拼接与 Shell 安全工具。
 *
 * 抽取自 DeployService，供 [BackupService]、[ReportBuilder] 及部署/浏览流程共享，
 * 保持远程路径拼接与 Shell 转义逻辑单一来源。
 */
object RemotePathUtils {

    /** 拼接远程基础目录与相对路径，去除多余斜杠。 */
    fun joinRemotePath(base: String, relativePath: String): String {
        val normalizedBase = base.trimEnd('/')
        val normalizedRelative = relativePath.trim('/')
        return if (normalizedRelative.isBlank()) normalizedBase else "$normalizedBase/$normalizedRelative"
    }

    /**
     * POSIX Shell 单引号安全转义：将路径/参数包裹在单引号中，内部单引号替换为 `'\''`，
     * 防止 `$()`, 反引号, `;`, 空格等特殊字符触发命令注入。
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * 校验远程目录路径是否允许执行递归删除（拒绝空路径、根目录 `/`、`.`、`..` 等危险路径）。
     */
    fun isSafeToDeleteDirectory(path: String): Boolean {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isBlank()) return false
        val stripped = trimmed.trimEnd('/')
        if (stripped.isEmpty() || stripped == "." || stripped == "..") return false
        val normalized = runCatching {
            Paths.get(if (stripped.startsWith('/')) stripped else "/$stripped").normalize().toString().replace('\\', '/')
        }.getOrNull() ?: return false
        return normalized.isNotBlank() && normalized != "/" && normalized != "." && normalized != ".."
    }
}
