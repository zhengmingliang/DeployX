package com.alianga.idea.filesync.service

import com.alianga.idea.filesync.model.*
import com.alianga.idea.filesync.ssh.RsyncWrapper
import com.alianga.idea.filesync.ssh.SshConnection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 部署服务 - 负责完整部署流程（备份 + 上传 + 解压）
 */
@Service
class DeployService {

    companion object {
        private val LOG = Logger.getInstance(DeployService::class.java)

        fun getInstance(): DeployService =
            ApplicationManager.getApplication().getService(DeployService::class.java)
    }

    data class UploadGroupKey(
        val serverId: String,
        val mappingId: String,
        val sourceBaseDir: String,
        val remoteBaseDir: String,
        val excludePatterns: List<String>,
        val preCommand: String?,
        val postCommand: String?
    )

    private val rsyncWrapper = RsyncWrapper()

    /**
     * Upload-only 批量上传。按 server/mapping/root/excludes/commands 分组，使用 rsync --files-from 合并上传。
     */
    fun uploadBatch(
        items: List<UploadItem>,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): List<SyncResult> {
        if (items.isEmpty()) return emptyList()

        val groups = items.groupBy {
            UploadGroupKey(
                serverId = it.serverId,
                mappingId = it.mappingId,
                sourceBaseDir = it.sourceBaseDir.trimEnd('/'),
                remoteBaseDir = it.remoteBaseDir.trimEnd('/'),
                excludePatterns = it.excludePatterns,
                preCommand = it.preCommand,
                postCommand = it.postCommand
            )
        }

        val results = mutableListOf<SyncResult>()
        groups.forEach { (key, groupItems) ->
            val server = ServerManager.getInstance().getServer(key.serverId)
            if (server == null) {
                val result = SyncResult(false, error = "服务器不存在: ${key.serverId}")
                results.add(result)
                logCallback?.invoke("[ERROR] ${result.error}")
                return@forEach
            }

            logCallback?.invoke("========== 上传分组 ==========")
            logCallback?.invoke("服务器: ${server.displayAddress}")
            logCallback?.invoke("本地根目录: ${key.sourceBaseDir}")
            logCallback?.invoke("远程根目录: ${key.remoteBaseDir}")
            logCallback?.invoke("文件数: ${groupItems.size}")

            val sshConnection = if (!key.preCommand.isNullOrBlank() || !key.postCommand.isNullOrBlank()) {
                SshConnection(server)
            } else null

            try {
                if (sshConnection != null) {
                    logCallback?.invoke("正在连接服务器 ${server.displayAddress} 以执行命令...")
                    if (!sshConnection.connect()) {
                        val result = SyncResult(false, error = "无法连接服务器执行命令: ${server.displayAddress}")
                        results.add(result)
                        logCallback?.invoke("[ERROR] ${result.error}")
                        return@forEach
                    }
                }

                if (!key.preCommand.isNullOrBlank()) {
                    logCallback?.invoke("[PRE] 执行上传前命令: ${key.preCommand}")
                    val preResult = sshConnection!!.executeCommand(key.preCommand)
                    if (preResult.output.isNotBlank()) logCallback?.invoke("[PRE] 输出: ${preResult.output.trim()}")
                    if (!preResult.success) logCallback?.invoke("[WARN] 上传前命令失败 (${preResult.exitCode}): ${preResult.error}")
                }

                val relativePaths = groupItems.map { item ->
                    if (item.isDirectory) item.relativePath.trimEnd('/') + "/" else item.relativePath.trim('/')
                }.filter { it.isNotBlank() }

                val result = rsyncWrapper.syncFilesFrom(
                    sourceBaseDir = key.sourceBaseDir,
                    remoteBaseDir = key.remoteBaseDir,
                    relativePaths = relativePaths,
                    serverConfig = server,
                    options = SyncOptions(excludePatterns = key.excludePatterns),
                    logCallback = logCallback,
                    progressCallback = progressCallback
                )
                results.add(result)

                if (!key.postCommand.isNullOrBlank()) {
                    logCallback?.invoke("[POST] 执行上传后命令: ${key.postCommand}")
                    val postResult = sshConnection!!.executeCommand(key.postCommand)
                    if (postResult.output.isNotBlank()) logCallback?.invoke("[POST] 输出: ${postResult.output.trim()}")
                    if (!postResult.success) logCallback?.invoke("[WARN] 上传后命令失败 (${postResult.exitCode}): ${postResult.error}")
                }
            } finally {
                sshConnection?.disconnect()
            }
        }

        return results
    }

    /**
     * 完整部署
     */
    fun deploy(
        request: DeployRequest,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): DeployResult {
        val startTime = System.currentTimeMillis()
        val taskId = UUID.randomUUID().toString().substring(0, 8)

        LOG.info("Starting deploy task $taskId: ${request.localPath} -> ${request.serverId}:${request.remotePath}")
        logCallback?.invoke("========== 部署任务 $taskId ==========")
        logCallback?.invoke("本地路径: ${request.localPath}")
        logCallback?.invoke("远程路径: ${request.serverId}:${request.remotePath}")

        val server = ServerManager.getInstance().getServer(request.serverId)
            ?: return DeployResult(
                success = false,
                taskId = taskId,
                error = "服务器不存在: ${request.serverId}",
                logs = listOf("错误: 服务器不存在: ${request.serverId}")
            )

        val sshConnection = SshConnection(server)

        try {
            // 建立 SSH 连接
            logCallback?.invoke("正在连接服务器 ${server.displayAddress}...")
            if (!sshConnection.connect()) {
                logCallback?.invoke("[ERROR] 无法连接到服务器: ${server.displayAddress}")
                return DeployResult(
                    success = false,
                    taskId = taskId,
                    error = "无法连接到服务器: ${server.displayAddress}",
                    logs = listOf("错误: 无法连接到服务器")
                )
            }
            logCallback?.invoke("SSH 连接成功")

            // 步骤 0: 执行上传前命令（可选）
            if (!request.preCommand.isNullOrBlank()) {
                logCallback?.invoke("[PRE] 执行上传前命令: ${request.preCommand}")
                val preResult = sshConnection.executeCommand(request.preCommand)
                if (preResult.output.isNotBlank()) {
                    logCallback?.invoke("[PRE] 输出: ${preResult.output.trim()}")
                }
                if (!preResult.success) {
                    logCallback?.invoke("[WARN] 上传前命令执行失败 (exit code: ${preResult.exitCode}): ${preResult.error}")
                    // 前置命令失败不阻断部署，只警告
                } else {
                    logCallback?.invoke("[PRE] 命令执行成功")
                }
            }

            // 步骤 1: 备份（可选）- 只备份正在上传的文件/目录，而非整个远程目录
            val backupPath = if (!request.backupDir.isNullOrBlank()) {
                val localFile = File(request.localPath)
                // 备份源：优先使用配置的 backupSource，否则自动推断
                val backupSource = if (!request.backupSource.isNullOrBlank()) {
                    request.backupSource
                } else {
                    "${request.remotePath}/${localFile.name}"
                }
                logCallback?.invoke("[1/3] 备份: $backupSource → ${request.backupDir}")
                val backupResult = doBackup(sshConnection, backupSource, request.backupDir!!, logCallback)
                if (backupResult.success) {
                    logCallback?.invoke("备份成功: ${backupResult.path}")
                    backupResult.path
                } else {
                    logCallback?.invoke("[ERROR] 备份失败: ${backupResult.error}")
                    return DeployResult(
                        success = false,
                        taskId = taskId,
                        error = "备份失败: ${backupResult.error}",
                        logs = listOf("备份失败: ${backupResult.error}"),
                        duration = System.currentTimeMillis() - startTime
                    )
                }
            } else {
                logCallback?.invoke("[1/3] 跳过备份（未配置备份目录）")
                null
            }

            // 步骤 2: 上传文件
            logCallback?.invoke("[2/3] 上传文件...")
            val syncOptions = SyncOptions(excludePatterns = request.excludePatterns)
            val syncResult = rsyncWrapper.sync(
                request.localPath,
                request.remotePath,
                server,
                syncOptions,
                logCallback,
                progressCallback
            )

            if (!syncResult.success) {
                logCallback?.invoke("[ERROR] 上传失败: ${syncResult.error}")
                // 记录失败历史
                saveHistory(request, syncResult, startTime, HistoryRecord.OperationStatus.FAILED)
                return DeployResult(
                    success = false,
                    taskId = taskId,
                    backupPath = backupPath,
                    error = "上传失败: ${syncResult.error}",
                    logs = listOf("上传失败: ${syncResult.error}"),
                    duration = System.currentTimeMillis() - startTime
                )
            }
            logCallback?.invoke("上传完成")

            // 步骤 3: 解压（可选）
            if (!request.unzipDest.isNullOrBlank()) {
                logCallback?.invoke("[3/3] 解压远程文件...")
                val filename = File(request.localPath).name
                val remoteFile = "${request.remotePath}/$filename"
                val unzipResult = doUnzip(sshConnection, remoteFile, request.unzipDest)

                if (unzipResult.success) {
                    logCallback?.invoke("解压成功: ${request.unzipDest}")
                } else {
                    logCallback?.invoke("[ERROR] 解压失败: ${unzipResult.error}")
                    saveHistory(request, syncResult, startTime, HistoryRecord.OperationStatus.FAILED)
                    return DeployResult(
                        success = false,
                        taskId = taskId,
                        backupPath = backupPath,
                        transferredFiles = syncResult.transferredFiles,
                        totalSize = syncResult.totalSize,
                        error = "解压失败: ${unzipResult.error}",
                        duration = System.currentTimeMillis() - startTime
                    )
                }
            } else {
                logCallback?.invoke("[3/3] 跳过解压（未配置解压目标）")
            }

            // 步骤 4: 执行上传后命令（可选）
            if (!request.postCommand.isNullOrBlank()) {
                logCallback?.invoke("[POST] 执行上传后命令: ${request.postCommand}")
                val postResult = sshConnection.executeCommand(request.postCommand)
                if (postResult.output.isNotBlank()) {
                    logCallback?.invoke("[POST] 输出: ${postResult.output.trim()}")
                }
                if (!postResult.success) {
                    logCallback?.invoke("[WARN] 上传后命令执行失败 (exit code: ${postResult.exitCode}): ${postResult.error}")
                } else {
                    logCallback?.invoke("[POST] 命令执行成功")
                }
            }

            val duration = System.currentTimeMillis() - startTime
            logCallback?.invoke("========== 部署完成！耗时: ${duration}ms ==========")

            // 记录成功历史
            saveHistory(request, syncResult, startTime, HistoryRecord.OperationStatus.SUCCESS)

            return DeployResult(
                success = true,
                taskId = taskId,
                backupPath = backupPath,
                transferredFiles = syncResult.transferredFiles,
                totalSize = syncResult.totalSize,
                duration = duration
            )

        } catch (e: Exception) {
            LOG.error("Deploy failed", e)
            logCallback?.invoke("[ERROR] 部署异常: ${e.message}")
            return DeployResult(
                success = false,
                taskId = taskId,
                error = "部署异常: ${e.message}",
                duration = System.currentTimeMillis() - startTime
            )
        } finally {
            sshConnection.disconnect()
        }
    }

    /**
     * 快速推送
     */
    fun push(
        localPath: String,
        serverId: String? = null,
        includePreCommand: Boolean = false,
        includePostCommand: Boolean = false,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): DeployResult {
        LOG.info("Quick push: $localPath")

        val resolvedMappings = MappingManager.getInstance().resolveMappingsByLocalPath(localPath)
        if (resolvedMappings.isEmpty()) {
            logCallback?.invoke("[ERROR] 未找到匹配的映射，请先配置目录映射")
            return DeployResult(
                success = false,
                error = "未找到匹配的映射，请先配置目录映射"
            )
        }

        val resolvedMapping = if (serverId != null) {
            resolvedMappings.firstOrNull { it.mapping.serverId == serverId } ?: resolvedMappings.first()
        } else {
            resolvedMappings.first()
        }
        val mapping = resolvedMapping.mapping

        val targetServerId = serverId ?: mapping.serverId
        logCallback?.invoke("匹配映射: ${mapping.name} → ${targetServerId}:${resolvedMapping.resolvedRemoteDir}")

        val request = DeployRequest(
            localPath = localPath,
            serverId = targetServerId,
            remotePath = resolvedMapping.resolvedRemoteDir,
            backupDir = null,
            backupSource = null,
            unzipDest = null,
            excludePatterns = mapping.exclude,
            preCommand = if (includePreCommand && mapping.effectivePreCommandEnabled) mapping.preCommand.ifBlank { null } else null,
            postCommand = if (includePostCommand && mapping.effectivePostCommandEnabled) mapping.postCommand.ifBlank { null } else null
        )

        return deploy(request, logCallback, progressCallback)
    }

    /**
     * 从历史记录重新部署
     */
    fun redeploy(
        record: HistoryRecord,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): DeployResult {
        logCallback?.invoke("========== 重新部署 ==========")
        logCallback?.invoke("原始操作: ${record.summary}")
        return deploy(record.toDeployRequest(), logCallback, progressCallback)
    }

    /**
     * 保存历史记录
     */
    private fun saveHistory(
        request: DeployRequest,
        syncResult: com.alianga.idea.filesync.model.SyncResult,
        startTime: Long,
        status: com.alianga.idea.filesync.model.HistoryRecord.OperationStatus
    ) {
        HistoryManager.getInstance().addRecord(
            HistoryRecord(
                type = HistoryRecord.OperationType.DEPLOY,
                sourcePath = request.localPath,
                serverId = request.serverId,
                targetPath = request.remotePath,
                fileCount = syncResult.transferredFiles,
                fileSize = syncResult.totalSize,
                duration = System.currentTimeMillis() - startTime,
                status = status,
                backupDir = request.backupDir ?: "",
                unzipDest = request.unzipDest ?: "",
                excludePatterns = request.excludePatterns,
                preCommand = request.preCommand ?: "",
                postCommand = request.postCommand ?: ""
            )
        )
    }

    /**
     * 执行备份
     * - 压缩包文件 → 加日期后缀直接复制到备份目录
     * - 目录或非压缩文件 → 压缩为 tar.gz（名称+日期），移动到备份目录
     *
     * @param backupSource 远程源路径（具体的文件或目录）
     * @param backupDir 备份目标目录
     */
    private fun doBackup(
        sshConnection: SshConnection,
        backupSource: String,
        backupDir: String,
        logCallback: ((String) -> Unit)? = null
    ): BackupResult {
        // 检查源文件/目录是否存在
        val checkResult = sshConnection.executeCommand("test -e $backupSource")
        if (!checkResult.success) {
            logCallback?.invoke("远程文件不存在，首次部署，跳过备份: $backupSource")
            return BackupResult(true, null)
        }

        // 确保备份目录存在
        sshConnection.executeCommand("mkdir -p $backupDir")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sourceName = File(backupSource).name
        val isCompressed = sourceName.endsWith(".zip", true) ||
                sourceName.endsWith(".tar.gz", true) ||
                sourceName.endsWith(".tgz", true) ||
                sourceName.endsWith(".gz", true) ||
                sourceName.endsWith(".rar", true) ||
                sourceName.endsWith(".7z", true)

        val backupResult = if (isCompressed) {
            // 压缩包：加日期后缀，直接复制
            val baseName = sourceName.substringBeforeLast(".")
            val ext = sourceName.substringAfterLast(".")
            val backupFile = "$backupDir/${baseName}_bak_${timestamp}.$ext"
            logCallback?.invoke("压缩包备份: $backupSource → $backupFile")
            sshConnection.executeCommand("cp $backupSource $backupFile")
        } else {
            // 目录或非压缩文件：压缩为 tar.gz
            val tarName = "${sourceName}_${timestamp}.tar.gz"
            val tarPath = "$backupDir/$tarName"
            val sourceParent = File(backupSource).parent
            logCallback?.invoke("压缩备份: $backupSource → $tarPath")
            sshConnection.executeCommand(
                "tar -czf $tarPath -C $sourceParent $sourceName"
            )
        }

        return if (backupResult.success) {
            logCallback?.invoke("备份完成")
            BackupResult(true, backupDir)
        } else {
            logCallback?.invoke("[WARN] 备份失败: ${backupResult.error}")
            BackupResult(false, error = backupResult.error)
        }
    }

    /**
     * 执行解压
     */
    private fun doUnzip(
        sshConnection: SshConnection,
        zipPath: String,
        destDir: String
    ): UnzipResult {
        val checkResult = sshConnection.executeCommand("test -f $zipPath")
        if (!checkResult.success) {
            return UnzipResult(false, error = "文件不存在: $zipPath")
        }

        sshConnection.executeCommand("mkdir -p $destDir")

        val result = sshConnection.executeCommand("unzip -o $zipPath -d $destDir")
        return if (result.success) {
            UnzipResult(true)
        } else {
            UnzipResult(false, error = result.error)
        }
    }

    private data class BackupResult(val success: Boolean, val path: String? = null, val error: String? = null)
    private data class UnzipResult(val success: Boolean, val error: String? = null)
}
