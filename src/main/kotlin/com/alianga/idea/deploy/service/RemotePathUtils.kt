package com.alianga.idea.deploy.service

/**
 * 远程路径拼接工具。
 *
 * 抽取自 DeployService，供 [BackupService]、[ReportBuilder] 及部署流程共享，
 * 保持远程路径拼接逻辑单一来源。
 */
object RemotePathUtils {

    /** 拼接远程基础目录与相对路径，去除多余斜杠。 */
    fun joinRemotePath(base: String, relativePath: String): String {
        val normalizedBase = base.trimEnd('/')
        val normalizedRelative = relativePath.trim('/')
        return if (normalizedRelative.isBlank()) normalizedBase else "$normalizedBase/$normalizedRelative"
    }
}
