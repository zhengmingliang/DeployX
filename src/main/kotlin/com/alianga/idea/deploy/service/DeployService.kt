package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.*
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.alianga.idea.deploy.ssh.SshConnection
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

    data class DeployGroupKey(
        val serverId: String,
        val mappingId: String,
        val sourceBaseDir: String,
        val remoteBaseDir: String,
        val excludePatterns: List<String>,
        val backupDir: String?,
        val backupSource: String?,
        val unzipDest: String?,
        val preCommand: String?,
        val postCommand: String?
    )

    private val rsyncWrapper = RsyncWrapper()

    /**
     * Upload-only 批量上传。按 server/mapping/root/excludes/commands 分组，使用 rsync --files-from 合并上传。
     */
    fun uploadBatch(
        items: List<UploadItem>,
        dryRun: Boolean = false,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null,
        serverLogCallback: ((serverId: String, line: String) -> Unit)? = null
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
            val groupLog: (String) -> Unit = { line ->
                if (serverLogCallback != null) serverLogCallback.invoke(key.serverId, line) else logCallback?.invoke(line)
            }
            val server = ServerManager.getInstance().getServer(key.serverId)
            if (server == null) {
                val result = SyncResult(false, error = "服务器不存在: ${key.serverId}")
                results.add(result)
                groupLog("[ERROR] ${result.error}")
                return@forEach
            }

            groupLog("========== ${if (dryRun) "预览" else "上传"}分组 ==========")
            groupLog("服务器: ${server.displayAddress}")
            groupLog("本地根目录: ${key.sourceBaseDir}")
            groupLog("远程根目录: ${key.remoteBaseDir}")
            groupLog("文件数: ${groupItems.size}")

            val sshConnection = if (!dryRun && (!key.preCommand.isNullOrBlank() || !key.postCommand.isNullOrBlank())) {
                SshConnection(server)
            } else null

            try {
                if (sshConnection != null) {
                    groupLog("正在连接服务器 ${server.displayAddress} 以执行命令...")
                    val connectResult = sshConnection.connectWithDetails()
                    if (!connectResult.success) {
                        val errorDetail = if (connectResult.exceptionClass != null) " (${connectResult.exceptionClass})" else ""
                        val result = SyncResult(false, error = "无法连接服务器执行命令: ${server.displayAddress}$errorDetail")
                        results.add(result)
                        groupLog("[ERROR] ${result.error}")
                        if (connectResult.errorMessage != null) {
                            groupLog("[DETAIL] ${connectResult.errorMessage}")
                        }
                        return@forEach
                    }
                }

                if (!dryRun && !key.preCommand.isNullOrBlank()) {
                    groupLog("[PRE] 执行上传前命令: ${key.preCommand}")
                    val preResult = sshConnection!!.executeCommand(key.preCommand)
                    if (preResult.output.isNotBlank()) groupLog("[PRE] 输出: ${preResult.output.trim()}")
                    if (!preResult.success) groupLog("[WARN] 上传前命令失败 (${preResult.exitCode}): ${preResult.error}")
                }

                val relativePaths = groupItems.map { item ->
                    if (item.isDirectory) item.relativePath.trimEnd('/') + "/" else item.relativePath.trim('/')
                }.filter { it.isNotBlank() }

                val result = TransferService.getInstance().transferFilesFrom(
                    sourceBaseDir = key.sourceBaseDir,
                    remoteBaseDir = key.remoteBaseDir,
                    relativePaths = relativePaths,
                    serverConfig = server,
                    options = SyncOptions(excludePatterns = key.excludePatterns, dryRun = dryRun),
                    logCallback = groupLog,
                    progressCallback = progressCallback
                )

                val resultWithReport = result.copy(
                    reportGroup = buildReportGroup(
                        server = server,
                        sourceBaseDir = key.sourceBaseDir,
                        remoteBaseDir = key.remoteBaseDir,
                        localPaths = groupItems.map { it.localPath },
                        relativePaths = relativePaths,
                        success = result.success,
                        duration = result.duration,
                        totalSize = result.totalSize,
                        output = result.output
                    )
                )
                results.add(resultWithReport)

                if (!dryRun && !key.postCommand.isNullOrBlank()) {
                    groupLog("[POST] 执行上传后命令: ${key.postCommand}")
                    val postResult = sshConnection!!.executeCommand(key.postCommand)
                    if (postResult.output.isNotBlank()) groupLog("[POST] 输出: ${postResult.output.trim()}")
                    if (!postResult.success) groupLog("[WARN] 上传后命令失败 (${postResult.exitCode}): ${postResult.error}")
                }
            } finally {
                sshConnection?.disconnect()
            }
        }

        return results
    }

    /**
     * 完整部署的批量分组执行：每组 pre/backup/upload/unzip/post 各执行一次。
     */
    fun deployBatch(
        items: List<DeployItem>,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null,
        serverLogCallback: ((serverId: String, line: String) -> Unit)? = null
    ): List<DeployResult> {
        if (items.isEmpty()) return emptyList()
        val groups = items.groupBy {
            DeployGroupKey(
                serverId = it.serverId,
                mappingId = it.mappingId,
                sourceBaseDir = it.sourceBaseDir.trimEnd('/'),
                remoteBaseDir = it.remoteBaseDir.trimEnd('/'),
                excludePatterns = it.excludePatterns,
                backupDir = it.backupDir?.trimEnd('/'),
                backupSource = it.backupSource?.trimEnd('/'),
                unzipDest = it.unzipDest?.trimEnd('/'),
                preCommand = it.preCommand?.ifBlank { null },
                postCommand = it.postCommand?.ifBlank { null }
            )
        }

        val results = mutableListOf<DeployResult>()
        groups.forEach { (key, groupItems) ->
            val groupLog: (String) -> Unit = { line ->
                if (serverLogCallback != null) serverLogCallback.invoke(key.serverId, line) else logCallback?.invoke(line)
            }
            val startTime = System.currentTimeMillis()
            val taskId = UUID.randomUUID().toString().substring(0, 8)
            val server = ServerManager.getInstance().getServer(key.serverId)
            if (server == null) {
                val result = DeployResult(false, taskId = taskId, error = "服务器不存在: ${key.serverId}")
                results.add(result)
                groupLog("[ERROR] ${result.error}")
                return@forEach
            }

            groupLog("========== 部署分组 $taskId ==========")
            groupLog("服务器: ${server.displayAddress}")
            groupLog("本地根目录: ${key.sourceBaseDir}")
            groupLog("远程根目录: ${key.remoteBaseDir}")
            groupLog("文件数: ${groupItems.size}")

            // 计算需要 SSH 连接的步骤
            val needsSshConnection = !key.preCommand.isNullOrBlank() ||
                    !key.backupDir.isNullOrBlank() ||
                    !key.unzipDest.isNullOrBlank() ||
                    !key.postCommand.isNullOrBlank()

            // 相对路径预处理
            val relativePaths = groupItems.map { item ->
                if (item.isDirectory) item.relativePath.trimEnd('/') + "/" else item.relativePath.trim('/')
            }.filter { it.isNotBlank() }

            // 先建立 SSH 连接（如果需要），确保连接可用后再上传
            val sshConnection = if (needsSshConnection) {
                groupLog("正在连接服务器 ${server.displayAddress}...")
                val conn = SshConnection(server)
                val connectResult = conn.connectWithDetails()
                if (!connectResult.success) {
                    val errorDetail = if (connectResult.exceptionClass != null) " (${connectResult.exceptionClass})" else ""
                    val result = DeployResult(
                        false,
                        taskId = taskId,
                        error = "无法连接到服务器: ${server.displayAddress}$errorDetail",
                        duration = System.currentTimeMillis() - startTime
                    )
                    results.add(result)
                    groupLog("[ERROR] ${result.error}")
                    if (connectResult.errorMessage != null) {
                        groupLog("[DETAIL] ${connectResult.errorMessage}")
                    }
                    return@forEach
                }
                groupLog("SSH 连接成功")
                conn
            } else null

            try {
                // 步骤 1: 上传前命令（在备份和上传之前执行）
                if (!key.preCommand.isNullOrBlank()) {
                    groupLog("[PRE] 执行上传前命令: ${key.preCommand}")
                    val preResult = sshConnection!!.executeCommand(key.preCommand)
                    if (preResult.output.isNotBlank()) groupLog("[PRE] 输出: ${preResult.output.trim()}")
                    if (!preResult.success) groupLog("[WARN] 上传前命令执行失败 (${preResult.exitCode}): ${preResult.error}")
                    else groupLog("[PRE] 命令执行成功")
                }

                // 步骤 2: 备份（在上传之前执行，确保备份的是旧版本）
                var backupPath: String? = null
                val backupDir = key.backupDir
                if (sshConnection != null && !backupDir.isNullOrBlank()) {
                    val backupResult = if (!key.backupSource.isNullOrBlank()) {
                        groupLog("[1/${if (needsSshConnection) 4 else 2}] 备份配置源: ${key.backupSource} → $backupDir")
                        doBackup(sshConnection, key.backupSource, backupDir, groupLog)
                    } else {
                        groupLog("[1/${if (needsSshConnection) 4 else 2}] 备份本次选择的远程文件 → $backupDir")
                        doBackupSelected(sshConnection, key.remoteBaseDir, groupItems.map { it.relativePath }, backupDir, groupLog)
                    }
                    if (!backupResult.success) {
                        val result = DeployResult(
                            false,
                            taskId = taskId,
                            error = "备份失败: ${backupResult.error}",
                            duration = System.currentTimeMillis() - startTime
                        )
                        results.add(result)
                        groupLog("[ERROR] ${result.error}")
                        return@forEach
                    }
                    backupPath = backupResult.path
                } else {
                    groupLog("[1/${if (needsSshConnection) 4 else 2}] 跳过备份（未配置备份目录）")
                }

                // 步骤 3: 上传文件（使用 rsync 命令）
                val uploadStep = if (needsSshConnection) 2 else 1
                groupLog("[$uploadStep/${if (needsSshConnection) 4 else 2}] 批量上传文件...")
                val syncResult = TransferService.getInstance().transferFilesFrom(
                    sourceBaseDir = key.sourceBaseDir,
                    remoteBaseDir = key.remoteBaseDir,
                    relativePaths = relativePaths,
                    serverConfig = server,
                    options = SyncOptions(excludePatterns = key.excludePatterns),
                    logCallback = groupLog,
                    progressCallback = progressCallback
                )
                if (!syncResult.success) {
                    val result = DeployResult(
                        false,
                        taskId = taskId,
                        backupPath = backupPath,
                        error = "上传失败: ${syncResult.error}",
                        duration = System.currentTimeMillis() - startTime
                    )
                    results.add(result)
                    groupLog("[ERROR] ${result.error}")
                    return@forEach
                }
                groupLog("上传完成")

                // 如果不需要 SSH 连接，直接完成
                if (!needsSshConnection) {
                    val duration = System.currentTimeMillis() - startTime
                    groupLog("========== 部署分组完成！耗时: ${duration}ms ==========")
                    val reportGroup = buildReportGroup(
                        server = server,
                        sourceBaseDir = key.sourceBaseDir,
                        remoteBaseDir = key.remoteBaseDir,
                        localPaths = groupItems.map { it.localPath },
                        relativePaths = groupItems.map { it.relativePath },
                        success = true,
                        duration = duration,
                        totalSize = syncResult.totalSize,
                        output = syncResult.output
                    )
                    val result = DeployResult(
                        success = true,
                        taskId = taskId,
                        transferredFiles = syncResult.transferredFiles,
                        totalSize = syncResult.totalSize,
                        duration = duration,
                        reportGroup = reportGroup
                    )
                    results.add(result)
                    saveGroupHistory(key, groupItems, syncResult, duration, HistoryRecord.OperationStatus.SUCCESS)
                    return@forEach
                }

                // 步骤 4: 解压
                if (!key.unzipDest.isNullOrBlank()) {
                    val zipItems = groupItems.filter { it.relativePath.endsWith(".zip", ignoreCase = true) }
                    when {
                        zipItems.isEmpty() -> groupLog("[3/4] 已配置解压，但本组没有 zip 文件，跳过解压")
                        zipItems.size == 1 -> {
                            val remoteZip = joinRemotePath(key.remoteBaseDir, zipItems.first().relativePath)
                            groupLog("[3/4] 解压远程文件: $remoteZip → ${key.unzipDest}")
                            val unzipResult = doUnzip(sshConnection!!, remoteZip, key.unzipDest)
                            if (!unzipResult.success) {
                                val result = DeployResult(
                                    false,
                                    taskId = taskId,
                                    backupPath = backupPath,
                                    transferredFiles = syncResult.transferredFiles,
                                    totalSize = syncResult.totalSize,
                                    error = "解压失败: ${unzipResult.error}",
                                    duration = System.currentTimeMillis() - startTime
                                )
                                results.add(result)
                                groupLog("[ERROR] ${result.error}")
                                return@forEach
                            }
                            groupLog("解压成功: ${key.unzipDest}")
                        }
                        else -> {
                            val result = DeployResult(
                                false,
                                taskId = taskId,
                                backupPath = backupPath,
                                transferredFiles = syncResult.transferredFiles,
                                totalSize = syncResult.totalSize,
                                error = "本组包含多个 zip 文件，无法安全执行单次解压，请单独部署或关闭解压",
                                duration = System.currentTimeMillis() - startTime
                            )
                            results.add(result)
                            groupLog("[ERROR] ${result.error}")
                            return@forEach
                        }
                    }
                } else {
                    groupLog("[3/4] 跳过解压（未配置解压目标）")
                }

                // 步骤 5: 上传后命令
                if (!key.postCommand.isNullOrBlank()) {
                    groupLog("[POST] 执行上传后命令: ${key.postCommand}")
                    val postResult = sshConnection!!.executeCommand(key.postCommand)
                    if (postResult.output.isNotBlank()) groupLog("[POST] 输出: ${postResult.output.trim()}")
                    if (!postResult.success) groupLog("[WARN] 上传后命令执行失败 (${postResult.exitCode}): ${postResult.error}")
                    else groupLog("[POST] 命令执行成功")
                }

                val duration = System.currentTimeMillis() - startTime
                groupLog("========== 部署分组完成！耗时: ${duration}ms ==========")
                val reportGroup = buildReportGroup(
                    server = server,
                    sourceBaseDir = key.sourceBaseDir,
                    remoteBaseDir = key.remoteBaseDir,
                    localPaths = groupItems.map { it.localPath },
                    relativePaths = groupItems.map { it.relativePath },
                    success = true,
                    duration = duration,
                    totalSize = syncResult.totalSize,
                    output = syncResult.output
                )
                val result = DeployResult(
                    success = true,
                    taskId = taskId,
                    backupPath = backupPath,
                    transferredFiles = syncResult.transferredFiles,
                    totalSize = syncResult.totalSize,
                    duration = duration,
                    reportGroup = reportGroup
                )
                results.add(result)
                saveGroupHistory(key, groupItems, syncResult, duration, HistoryRecord.OperationStatus.SUCCESS)
            } catch (e: Exception) {
                LOG.error("Batch deploy group failed", e)
                val result = DeployResult(false, taskId = taskId, error = "部署异常: ${e.message}", duration = System.currentTimeMillis() - startTime)
                results.add(result)
                groupLog("[ERROR] ${result.error}")
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

        // 判断是否需要 SSH 连接
        val needsSshConnection = !request.preCommand.isNullOrBlank() ||
                !request.backupDir.isNullOrBlank() ||
                !request.unzipDest.isNullOrBlank() ||
                !request.postCommand.isNullOrBlank()

        // 先建立 SSH 连接（如果需要），确保连接可用后再上传
        val sshConnection = if (needsSshConnection) {
            logCallback?.invoke("正在连接服务器 ${server.displayAddress}...")
            val conn = SshConnection(server)
            val connectResult = conn.connectWithDetails()
            if (!connectResult.success) {
                val errorDetail = if (connectResult.exceptionClass != null) " (${connectResult.exceptionClass})" else ""
                logCallback?.invoke("[ERROR] 无法连接到服务器: ${server.displayAddress}$errorDetail")
                if (connectResult.errorMessage != null) {
                    logCallback?.invoke("[DETAIL] ${connectResult.errorMessage}")
                }
                return DeployResult(
                    success = false,
                    taskId = taskId,
                    error = "无法连接到服务器: ${server.displayAddress}$errorDetail",
                    logs = listOfNotNull(
                        "错误: 无法连接到服务器",
                        connectResult.errorMessage?.let { "详情: $it" }
                    )
                )
            }
            logCallback?.invoke("SSH 连接成功")
            conn
        } else null

        try {
            // 步骤 1: 执行上传前命令（在备份和上传之前执行）
            if (!request.preCommand.isNullOrBlank()) {
                logCallback?.invoke("[PRE] 执行上传前命令: ${request.preCommand}")
                val preResult = sshConnection!!.executeCommand(request.preCommand)
                if (preResult.output.isNotBlank()) {
                    logCallback?.invoke("[PRE] 输出: ${preResult.output.trim()}")
                }
                if (!preResult.success) {
                    logCallback?.invoke("[WARN] 上传前命令执行失败 (exit code: ${preResult.exitCode}): ${preResult.error}")
                } else {
                    logCallback?.invoke("[PRE] 命令执行成功")
                }
            }

            // 步骤 2: 备份（在上传之前执行，确保备份的是旧版本）
            var backupPath: String? = null
            if (sshConnection != null && !request.backupDir.isNullOrBlank()) {
                val localFile = File(request.localPath)
                val backupSource = if (!request.backupSource.isNullOrBlank()) {
                    request.backupSource
                } else {
                    "${request.remotePath}/${localFile.name}"
                }
                val backupDir = request.backupDir!!
                logCallback?.invoke("[1/${if (needsSshConnection) 4 else 2}] 备份: $backupSource → $backupDir")
                val backupResult = doBackup(sshConnection, backupSource, backupDir, logCallback)
                if (backupResult.success) {
                    logCallback?.invoke("备份成功: ${backupResult.path}")
                    backupPath = backupResult.path
                } else {
                    logCallback?.invoke("[ERROR] 备份失败: ${backupResult.error}")
                    return DeployResult(
                        success = false,
                        taskId = taskId,
                        backupPath = backupResult.path,
                        error = "备份失败: ${backupResult.error}",
                        logs = listOf("备份失败: ${backupResult.error}"),
                        duration = System.currentTimeMillis() - startTime
                    )
                }
            } else {
                logCallback?.invoke("[1/${if (needsSshConnection) 4 else 2}] 跳过备份（未配置备份目录）")
            }

            // 步骤 3: 上传文件（使用 rsync 命令）
            val uploadStep = if (needsSshConnection) 2 else 1
            logCallback?.invoke("[$uploadStep/${if (needsSshConnection) 4 else 2}] 上传文件...")
            val syncOptions = SyncOptions(excludePatterns = request.excludePatterns)
            val syncResult = TransferService.getInstance().transfer(
                request.localPath,
                request.remotePath,
                server,
                syncOptions,
                logCallback,
                progressCallback
            )

            if (!syncResult.success) {
                logCallback?.invoke("[ERROR] 上传失败: ${syncResult.error}")
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

            // 如果不需要 SSH 连接，直接完成
            if (!needsSshConnection) {
                val duration = System.currentTimeMillis() - startTime
                logCallback?.invoke("========== 部署完成！耗时: ${duration}ms ==========")
                saveHistory(request, syncResult, startTime, HistoryRecord.OperationStatus.SUCCESS)
                val reportGroup = buildReportGroup(
                    server = server,
                    sourceBaseDir = File(request.localPath).parent ?: request.localPath,
                    remoteBaseDir = request.remotePath,
                    localPaths = listOf(request.localPath),
                    relativePaths = listOf(File(request.localPath).name),
                    success = true,
                    duration = duration,
                    totalSize = syncResult.totalSize,
                    output = syncResult.output
                )
                return DeployResult(
                    success = true,
                    taskId = taskId,
                    transferredFiles = syncResult.transferredFiles,
                    totalSize = syncResult.totalSize,
                    duration = duration,
                    reportGroup = reportGroup
                )
            }

            // 步骤 4: 解压（可选）
            if (!request.unzipDest.isNullOrBlank()) {
                logCallback?.invoke("[3/4] 解压远程文件...")
                val filename = File(request.localPath).name
                val remoteFile = "${request.remotePath}/$filename"
                val unzipResult = doUnzip(sshConnection!!, remoteFile, request.unzipDest)

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
                logCallback?.invoke("[3/4] 跳过解压（未配置解压目标）")
            }

            // 步骤 5: 执行上传后命令（可选）
            if (!request.postCommand.isNullOrBlank()) {
                logCallback?.invoke("[POST] 执行上传后命令: ${request.postCommand}")
                val postResult = sshConnection!!.executeCommand(request.postCommand)
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
            saveHistory(request, syncResult, startTime, HistoryRecord.OperationStatus.SUCCESS)

            val reportGroup = buildReportGroup(
                server = server,
                sourceBaseDir = File(request.localPath).parent ?: request.localPath,
                remoteBaseDir = request.remotePath,
                localPaths = listOf(request.localPath),
                relativePaths = listOf(File(request.localPath).name),
                success = true,
                duration = duration,
                totalSize = syncResult.totalSize,
                output = syncResult.output
            )

            return DeployResult(
                success = true,
                taskId = taskId,
                backupPath = backupPath,
                transferredFiles = syncResult.transferredFiles,
                totalSize = syncResult.totalSize,
                duration = duration,
                reportGroup = reportGroup
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
            sshConnection?.disconnect()
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

    private fun buildReportGroup(
        server: ServerConfig,
        sourceBaseDir: String,
        remoteBaseDir: String,
        localPaths: List<String>,
        relativePaths: List<String>,
        success: Boolean,
        duration: Long,
        totalSize: Long,
        output: String
    ): UpdateReportGroup {
        val normalizedRelativePaths = relativePaths.map { it.trim('/') }.filter { it.isNotBlank() }
        return UpdateReportGroup(
            serverId = server.id,
            serverName = server.name,
            serverAddress = server.displayAddress,
            sourceBaseDir = sourceBaseDir,
            remoteBaseDir = remoteBaseDir,
            selectedLocalPaths = localPaths,
            relativePaths = normalizedRelativePaths,
            remotePaths = normalizedRelativePaths.map { joinRemotePath(remoteBaseDir, it) },
            success = success,
            duration = duration,
            totalSize = totalSize,
            rsyncOutput = output
        )
    }

    private fun saveGroupHistory(
        key: DeployGroupKey,
        items: List<DeployItem>,
        syncResult: SyncResult,
        duration: Long,
        status: HistoryRecord.OperationStatus
    ) {
        val server = ServerManager.getInstance().getServer(key.serverId)
        val reportGroup = if (server != null) {
            buildReportGroup(
                server = server,
                sourceBaseDir = key.sourceBaseDir,
                remoteBaseDir = key.remoteBaseDir,
                localPaths = items.map { it.localPath },
                relativePaths = items.map { it.relativePath },
                success = status == HistoryRecord.OperationStatus.SUCCESS,
                duration = duration,
                totalSize = syncResult.totalSize,
                output = syncResult.output
            )
        } else null
        val reportText = reportGroup?.let {
            UpdateReportFormatter.format(UpdateReport(operationType = "DEPLOY", groups = listOf(it)))
        } ?: ""
        HistoryManager.getInstance().addRecord(
            HistoryRecord(
                type = HistoryRecord.OperationType.DEPLOY,
                sourcePath = key.sourceBaseDir,
                serverId = key.serverId,
                targetPath = key.remoteBaseDir,
                fileCount = items.size,
                fileSize = syncResult.totalSize,
                duration = duration,
                status = status,
                backupDir = key.backupDir ?: "",
                unzipDest = key.unzipDest ?: "",
                excludePatterns = key.excludePatterns,
                preCommand = key.preCommand ?: "",
                postCommand = key.postCommand ?: "",
                relativePaths = reportGroup?.relativePaths ?: emptyList(),
                remotePaths = reportGroup?.remotePaths ?: emptyList(),
                serverName = reportGroup?.serverName ?: "",
                serverAddress = reportGroup?.serverAddress ?: "",
                reportText = reportText
            )
        )
    }

    /**
     * 保存历史记录
     */
    private fun saveHistory(
        request: DeployRequest,
        syncResult: com.alianga.idea.deploy.model.SyncResult,
        startTime: Long,
        status: com.alianga.idea.deploy.model.HistoryRecord.OperationStatus
    ) {
        val server = ServerManager.getInstance().getServer(request.serverId)
        val reportGroup = if (server != null) {
            buildReportGroup(
                server = server,
                sourceBaseDir = File(request.localPath).parent ?: request.localPath,
                remoteBaseDir = request.remotePath,
                localPaths = listOf(request.localPath),
                relativePaths = listOf(File(request.localPath).name),
                success = status == HistoryRecord.OperationStatus.SUCCESS,
                duration = System.currentTimeMillis() - startTime,
                totalSize = syncResult.totalSize,
                output = syncResult.output
            )
        } else null
        val reportText = reportGroup?.let {
            UpdateReportFormatter.format(UpdateReport(operationType = "DEPLOY", groups = listOf(it)))
        } ?: ""
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
                postCommand = request.postCommand ?: "",
                relativePaths = reportGroup?.relativePaths ?: emptyList(),
                remotePaths = reportGroup?.remotePaths ?: emptyList(),
                serverName = reportGroup?.serverName ?: "",
                serverAddress = reportGroup?.serverAddress ?: "",
                reportText = reportText
            )
        )
    }

    /**
     * 将本次选择的多个远程文件/目录打成一个 tar.gz 备份包。
     */
    private fun doBackupSelected(
        sshConnection: SshConnection,
        remoteBaseDir: String,
        relativePaths: List<String>,
        backupDir: String,
        logCallback: ((String) -> Unit)? = null
    ): BackupResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val backupFile = "${backupDir.trimEnd('/')}/backup_${timestamp}.tar.gz"
        sshConnection.executeCommand("mkdir -p ${shellQuote(backupDir)}")

        val existingPaths = relativePaths.map { it.trim('/') }.filter { it.isNotBlank() }.filter { relativePath ->
            val remotePath = joinRemotePath(remoteBaseDir, relativePath)
            sshConnection.executeCommand("test -e ${shellQuote(remotePath)}").success
        }

        if (existingPaths.isEmpty()) {
            logCallback?.invoke("远程文件均不存在，首次部署，跳过备份")
            return BackupResult(true, null)
        }

        logCallback?.invoke("正在压缩备份 ${existingPaths.size} 个远程文件/目录 → $backupFile")
        existingPaths.forEach { logCallback?.invoke("  $it") }
        val quotedPaths = existingPaths.joinToString(" ") { shellQuote(it) }
        val result = sshConnection.executeCommand(
            "tar -czf ${shellQuote(backupFile)} -C ${shellQuote(remoteBaseDir)} $quotedPaths"
        )
        return if (result.success) {
            logCallback?.invoke("备份完成: $backupFile")
            BackupResult(true, backupFile)
        } else {
            BackupResult(false, error = result.error)
        }
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
        val checkResult = sshConnection.executeCommand("test -e ${shellQuote(backupSource)}")
        if (!checkResult.success) {
            logCallback?.invoke("远程文件不存在，首次部署，跳过备份: $backupSource")
            return BackupResult(true, null)
        }

        // 确保备份目录存在
        sshConnection.executeCommand("mkdir -p ${shellQuote(backupDir)}")

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
            sshConnection.executeCommand("cp ${shellQuote(backupSource)} ${shellQuote(backupFile)}")
        } else {
            // 目录或非压缩文件：压缩为 tar.gz
            val tarName = "${sourceName}_${timestamp}.tar.gz"
            val tarPath = "$backupDir/$tarName"
            val sourceParent = File(backupSource).parent
            logCallback?.invoke("压缩备份: $backupSource → $tarPath")
            sshConnection.executeCommand(
                "tar -czf ${shellQuote(tarPath)} -C ${shellQuote(sourceParent ?: ".")} ${shellQuote(sourceName)}"
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
        val checkResult = sshConnection.executeCommand("test -f ${shellQuote(zipPath)}")
        if (!checkResult.success) {
            return UnzipResult(false, error = "文件不存在: $zipPath")
        }

        sshConnection.executeCommand("mkdir -p ${shellQuote(destDir)}")

        val result = sshConnection.executeCommand("unzip -o ${shellQuote(zipPath)} -d ${shellQuote(destDir)}")
        return if (result.success) {
            UnzipResult(true)
        } else {
            UnzipResult(false, error = result.error)
        }
    }

    private fun joinRemotePath(base: String, relativePath: String): String {
        val normalizedBase = base.trimEnd('/')
        val normalizedRelative = relativePath.trim('/')
        return if (normalizedRelative.isBlank()) normalizedBase else "$normalizedBase/$normalizedRelative"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class BackupResult(val success: Boolean, val path: String? = null, val error: String? = null)
    private data class UnzipResult(val success: Boolean, val error: String? = null)
}
