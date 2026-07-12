package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.UpdateReportGroup

/**
 * 更新报告分组构建器 - 从 DeployService 抽取的纯函数。
 *
 * 将 rsync 传输结果转换为 [UpdateReportGroup]，无副作用、无服务依赖，便于复用与测试。
 */
object ReportBuilder {

    /**
     * 构建 [UpdateReportGroup]。
     *
     * [transferredFileList] 为 rsync 实际传输的文件（相对路径），会拼成远程全路径存入报告。
     * 增量同步跳过所有文件（0 transferred）时该列表为空，报告据此显示"无文件变更"，
     * 避免把选中的目录或未变更的文件误展示为"实际更新的文件"。
     */
    fun buildReportGroup(
        server: ServerConfig,
        sourceBaseDir: String,
        remoteBaseDir: String,
        localPaths: List<String>,
        relativePaths: List<String>,
        success: Boolean,
        duration: Long,
        totalSize: Long,
        output: String,
        transferredFileList: List<String> = emptyList(),
        backupPath: String? = null
    ): UpdateReportGroup {
        val normalizedRelativePaths = relativePaths.map { it.trim('/') }.filter { it.isNotBlank() }
        val transferredFiles = transferredFileList.map { RemotePathUtils.joinRemotePath(remoteBaseDir, it) }
        return UpdateReportGroup(
            serverId = server.id,
            serverName = server.name,
            serverAddress = server.displayAddress,
            sourceBaseDir = sourceBaseDir,
            remoteBaseDir = remoteBaseDir,
            selectedLocalPaths = localPaths,
            relativePaths = normalizedRelativePaths,
            remotePaths = normalizedRelativePaths.map { RemotePathUtils.joinRemotePath(remoteBaseDir, it) },
            transferredFiles = transferredFiles,
            success = success,
            duration = duration,
            totalSize = totalSize,
            rsyncOutput = output,
            backupPath = backupPath
        )
    }
}
