package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.*
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.alianga.idea.deploy.ssh.SshConnection
import com.alianga.idea.deploy.util.ScriptRefResolver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

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

    data class DownloadGroupKey(
        val serverId: String,
        val mappingId: String,
        val localBaseDir: String,
        val remoteBaseDir: String,
        val excludePatterns: List<String>
    )

    private val rsyncWrapper = RsyncWrapper()
    private val transferService = TransferService.getInstance()

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

        val results = Collections.synchronizedList(mutableListOf<SyncResult>())
        // 多服务器分组并行执行（每组内部串行，不同服务器并行）
        if (groups.size > 1) {
            val executor = Executors.newFixedThreadPool(groups.size.coerceAtMost(4))
            val latch = CountDownLatch(groups.size)
            groups.forEach { (key, groupItems) ->
                executor.submit {
                    try {
                        processUploadGroup(key, groupItems, results, serverLogCallback, logCallback, progressCallback, dryRun)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            executor.shutdown()
        } else {
            groups.forEach { (key, groupItems) ->
                processUploadGroup(key, groupItems, results, serverLogCallback, logCallback, progressCallback, dryRun)
            }
        }

        return results
    }

    /**
     * 处理单个上传分组（从 uploadBatch 抽取，支持并行调用）。
     */
    private fun processUploadGroup(
        key: UploadGroupKey,
        groupItems: List<UploadItem>,
        results: MutableList<SyncResult>,
        serverLogCallback: ((serverId: String, line: String) -> Unit)?,
        logCallback: ((String) -> Unit)?,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)?,
        dryRun: Boolean
    ) {
        val groupLog: (String) -> Unit = { line ->
            if (serverLogCallback != null) serverLogCallback.invoke(key.serverId, line) else logCallback?.invoke(line)
        }
        val server = ServerManager.getInstance().getServer(key.serverId)
        if (server == null) {
            val result = SyncResult(false, error = DeployXBundle.message("deploy.error.serverNotFound", key.serverId))
            results.add(result)
            groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
            return
        }

        groupLog(DeployXBundle.message(if (dryRun) "deploy.log.previewGroupHeader" else "deploy.log.uploadGroupHeader"))
        groupLog(DeployXBundle.message("deploy.log.server", server.displayAddress))
        groupLog(DeployXBundle.message("deploy.log.pushDirection"))
        groupLog(DeployXBundle.message("deploy.log.localRootDir", key.sourceBaseDir))
        groupLog(DeployXBundle.message("deploy.log.remoteRootDir", key.remoteBaseDir))
        groupLog(DeployXBundle.message("deploy.log.fileCount", groupItems.size))

        val sshConnection = if (!dryRun && (!key.preCommand.isNullOrBlank() || !key.postCommand.isNullOrBlank())) {
            SshConnection(server)
        } else null

        try {
            if (sshConnection != null) {
                groupLog(DeployXBundle.message("deploy.log.connectingServerForCommand", server.displayAddress))
                val connectResult = sshConnection.connectWithDetails()
                if (!connectResult.success) {
                    val errorDetail = if (connectResult.exceptionClass != null) " (${connectResult.exceptionClass})" else ""
                    val result = SyncResult(false, error = DeployXBundle.message("deploy.error.cannotConnectForCommand", "${server.displayAddress}$errorDetail"))
                    results.add(result)
                    groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
                    if (connectResult.errorMessage != null) {
                        groupLog(DeployXBundle.message("deploy.log.detail", connectResult.errorMessage))
                    }
                    return
                }
            }

            if (!dryRun && !key.preCommand.isNullOrBlank()) {
                val resolvedPreCommand = ScriptRefResolver.resolve(key.preCommand, server, key.remoteBaseDir)
                groupLog(DeployXBundle.message("deploy.log.preCommand", resolvedPreCommand))
                val conn = sshConnection ?: return
                val preResult = conn.executeCommand(resolvedPreCommand)
                if (preResult.output.isNotBlank()) groupLog(DeployXBundle.message("deploy.log.preOutput", preResult.output.trim()))
                if (!preResult.success) groupLog(DeployXBundle.message("deploy.log.preCommandFailedBatch", preResult.exitCode, preResult.error))
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
                    output = result.output,
                    transferredFileList = result.transferredFileList
                )
            )
            results.add(resultWithReport)

            if (!dryRun && !key.postCommand.isNullOrBlank()) {
                val resolvedPostCommand = ScriptRefResolver.resolve(key.postCommand, server, key.remoteBaseDir)
                groupLog(DeployXBundle.message("deploy.log.postCommand", resolvedPostCommand))
                val conn = sshConnection ?: return
                val postResult = conn.executeCommand(resolvedPostCommand)
                if (postResult.output.isNotBlank()) groupLog(DeployXBundle.message("deploy.log.postOutput", postResult.output.trim()))
                if (!postResult.success) groupLog(DeployXBundle.message("deploy.log.postCommandFailedBatch", postResult.exitCode, postResult.error))
            }
        } finally {
            sshConnection?.disconnect()
        }
    }

    /**
     * Download-only 批量下载。按 server/mapping/root/excludes 分组，使用 rsync --files-from 合并下载。
     */
    fun downloadBatch(
        items: List<DownloadItem>,
        dryRun: Boolean = false,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null,
        serverLogCallback: ((serverId: String, line: String) -> Unit)? = null
    ): List<SyncResult> {
        if (items.isEmpty()) return emptyList()

        val groups = items.groupBy {
            DownloadGroupKey(
                serverId = it.serverId,
                mappingId = it.mappingId,
                localBaseDir = it.localBaseDir.trimEnd('/'),
                remoteBaseDir = it.remoteBaseDir.trimEnd('/'),
                excludePatterns = it.excludePatterns
            )
        }

        val results = Collections.synchronizedList(mutableListOf<SyncResult>())
        // 多服务器分组并行执行（每组内部串行，不同服务器并行）
        if (groups.size > 1) {
            val executor = Executors.newFixedThreadPool(groups.size.coerceAtMost(4))
            val latch = CountDownLatch(groups.size)
            groups.forEach { (key, groupItems) ->
                executor.submit {
                    try {
                        processDownloadGroup(key, groupItems, results, serverLogCallback, logCallback, progressCallback, dryRun)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await()
            executor.shutdown()
        } else {
            groups.forEach { (key, groupItems) ->
                processDownloadGroup(key, groupItems, results, serverLogCallback, logCallback, progressCallback, dryRun)
            }
        }
        return results
    }

    private fun processDownloadGroup(
        key: DownloadGroupKey,
        groupItems: List<DownloadItem>,
        results: MutableList<SyncResult>,
        serverLogCallback: ((serverId: String, line: String) -> Unit)?,
        logCallback: ((String) -> Unit)?,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)?,
        dryRun: Boolean
    ) {
        val server = ServerManager.getInstance().getServer(key.serverId)
        if (server == null) {
            results.add(SyncResult(false, error = DeployXBundle.message("deploy.error.serverNotFound", key.serverId)))
            return
        }

        fun groupLog(line: String) {
            serverLogCallback?.invoke(key.serverId, line)
            logCallback?.invoke(line)
        }

        groupLog(DeployXBundle.message("deploy.log.downloadGroupHeader"))
        groupLog(DeployXBundle.message("deploy.log.server", key.serverId))
        groupLog(DeployXBundle.message("deploy.log.pullDirection"))
        groupLog(DeployXBundle.message("deploy.log.pullRemoteSource", key.remoteBaseDir))
        groupLog(DeployXBundle.message("deploy.log.pullLocalTarget", key.localBaseDir))
        groupLog(DeployXBundle.message("deploy.log.fileCount", groupItems.size))

        val relativePaths = groupItems.map { it.relativePath }
        val options = SyncOptions(
            direction = SyncDirection.PULL,
            excludePatterns = key.excludePatterns,
            dryRun = dryRun
        )

        val syncResult = transferService.downloadFilesFrom(
            localBaseDir = key.localBaseDir,
            remoteBaseDir = key.remoteBaseDir,
            relativePaths = relativePaths,
            serverConfig = server,
            options = options,
            logCallback = { groupLog(it) },
            progressCallback = progressCallback
        )
        results.add(syncResult)
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
                val result = DeployResult(false, taskId = taskId, error = DeployXBundle.message("deploy.error.serverNotFound", key.serverId))
                results.add(result)
                groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
                return@forEach
            }

            groupLog(DeployXBundle.message("deploy.log.deployGroupHeader", taskId))
            groupLog(DeployXBundle.message("deploy.log.server", server.displayAddress))
            groupLog(DeployXBundle.message("deploy.log.localRootDir", key.sourceBaseDir))
            groupLog(DeployXBundle.message("deploy.log.remoteRootDir", key.remoteBaseDir))
            groupLog(DeployXBundle.message("deploy.log.fileCount", groupItems.size))

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
                groupLog(DeployXBundle.message("deploy.log.connectingServer", server.displayAddress))
                val conn = SshConnection(server)
                val connectResult = conn.connectWithDetails()
                if (!connectResult.success) {
                    val errorDetail = if (connectResult.exceptionClass != null) " (${connectResult.exceptionClass})" else ""
                    val result = DeployResult(
                        false,
                        taskId = taskId,
                        error = DeployXBundle.message("deploy.error.cannotConnectToServer", "${server.displayAddress}$errorDetail"),
                        duration = System.currentTimeMillis() - startTime
                    )
                    results.add(result)
                    groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
                    if (connectResult.errorMessage != null) {
                        groupLog(DeployXBundle.message("deploy.log.detail", connectResult.errorMessage))
                    }
                    return@forEach
                }
                groupLog(DeployXBundle.message("deploy.log.sshConnected"))
                conn
            } else null

            try {
                // 步骤 1: 上传前命令（在备份和上传之前执行）
                if (!key.preCommand.isNullOrBlank()) {
                    val resolvedPreCommand = ScriptRefResolver.resolve(key.preCommand, server, key.remoteBaseDir)
                    groupLog(DeployXBundle.message("deploy.log.preCommand", resolvedPreCommand))
                    val conn = sshConnection ?: return@forEach
                    val preResult = conn.executeCommand(resolvedPreCommand)
                    if (preResult.output.isNotBlank()) groupLog(DeployXBundle.message("deploy.log.preOutput", preResult.output.trim()))
                    if (!preResult.success) groupLog(DeployXBundle.message("deploy.log.preCommandExecuteFailed", preResult.exitCode, preResult.error))
                    else groupLog(DeployXBundle.message("deploy.log.preCommandSuccess"))
                }

                // 步骤 2: 备份（在上传之前执行，确保备份的是旧版本）
                var backupPath: String? = null
                val backupDir = key.backupDir
                if (sshConnection != null && !backupDir.isNullOrBlank()) {
                    val backupResult = if (!key.backupSource.isNullOrBlank()) {
                        groupLog(DeployXBundle.message("deploy.log.backupConfigSource", if (needsSshConnection) 4 else 2, key.backupSource, backupDir))
                        doBackup(sshConnection, key.backupSource, backupDir, groupLog)
                    } else {
                        groupLog(DeployXBundle.message("deploy.log.backupSelectedFiles", if (needsSshConnection) 4 else 2, backupDir))
                        doBackupSelected(sshConnection, key.remoteBaseDir, groupItems.map { it.relativePath }, backupDir, groupLog)
                    }
                    if (!backupResult.success) {
                        val result = DeployResult(
                            false,
                            taskId = taskId,
                            error = DeployXBundle.message("deploy.error.backupFailed", backupResult.error ?: ""),
                            duration = System.currentTimeMillis() - startTime
                        )
                        results.add(result)
                        groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
                        return@forEach
                    }
                    backupPath = backupResult.path
                } else {
                    groupLog(DeployXBundle.message("deploy.log.skipBackup", if (needsSshConnection) 4 else 2))
                }

                // 步骤 3: 上传文件（使用 rsync 命令）
                val uploadStep = if (needsSshConnection) 2 else 1
                groupLog(DeployXBundle.message("deploy.log.batchUpload", uploadStep, if (needsSshConnection) 4 else 2))
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
                        error = DeployXBundle.message("deploy.error.uploadFailed", syncResult.error ?: ""),
                        duration = System.currentTimeMillis() - startTime
                    )
                    results.add(result)
                    groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
                    return@forEach
                }
                groupLog(DeployXBundle.message("deploy.log.uploadComplete"))

                // 如果不需要 SSH 连接，直接完成
                if (!needsSshConnection) {
                    val duration = System.currentTimeMillis() - startTime
                    groupLog(DeployXBundle.message("deploy.log.deployGroupComplete", duration))
                    val reportGroup = buildReportGroup(
                        server = server,
                        sourceBaseDir = key.sourceBaseDir,
                        remoteBaseDir = key.remoteBaseDir,
                        localPaths = groupItems.map { it.localPath },
                        relativePaths = groupItems.map { it.relativePath },
                        success = true,
                        duration = duration,
                        totalSize = syncResult.totalSize,
                        output = syncResult.output,
                        transferredFileList = syncResult.transferredFileList
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
                        zipItems.isEmpty() -> groupLog(DeployXBundle.message("deploy.log.skipUnzipNoZip"))
                        zipItems.size == 1 -> {
                            val remoteZip = joinRemotePath(key.remoteBaseDir, zipItems.first().relativePath)
                            groupLog(DeployXBundle.message("deploy.log.unzipRemoteFile", remoteZip, key.unzipDest))
                            val conn = sshConnection ?: return@forEach
                            val unzipResult = doUnzip(conn, remoteZip, key.unzipDest)
                            if (!unzipResult.success) {
                                val result = DeployResult(
                                    false,
                                    taskId = taskId,
                                    backupPath = backupPath,
                                    transferredFiles = syncResult.transferredFiles,
                                    totalSize = syncResult.totalSize,
                                    error = DeployXBundle.message("deploy.error.unzipFailed", unzipResult.error ?: ""),
                                    duration = System.currentTimeMillis() - startTime
                                )
                                results.add(result)
                                groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
                                return@forEach
                            }
                            groupLog(DeployXBundle.message("deploy.log.unzipSuccess", key.unzipDest))
                        }
                        else -> {
                            val result = DeployResult(
                                false,
                                taskId = taskId,
                                backupPath = backupPath,
                                transferredFiles = syncResult.transferredFiles,
                                totalSize = syncResult.totalSize,
                                error = DeployXBundle.message("deploy.error.multipleZipFiles"),
                                duration = System.currentTimeMillis() - startTime
                            )
                            results.add(result)
                            groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
                            return@forEach
                        }
                    }
                } else {
                    groupLog(DeployXBundle.message("deploy.log.skipUnzipNoDest"))
                }

                // 步骤 5: 上传后命令
                if (!key.postCommand.isNullOrBlank()) {
                    val resolvedPostCommand = ScriptRefResolver.resolve(key.postCommand, server, key.remoteBaseDir)
                    groupLog(DeployXBundle.message("deploy.log.postCommand", resolvedPostCommand))
                    val conn = sshConnection ?: return@forEach
                    val postResult = conn.executeCommand(resolvedPostCommand)
                    if (postResult.output.isNotBlank()) groupLog(DeployXBundle.message("deploy.log.postOutput", postResult.output.trim()))
                    if (!postResult.success) groupLog(DeployXBundle.message("deploy.log.postCommandExecuteFailed", postResult.exitCode, postResult.error))
                    else groupLog(DeployXBundle.message("deploy.log.postCommandSuccess"))
                }

                val duration = System.currentTimeMillis() - startTime
                groupLog(DeployXBundle.message("deploy.log.deployGroupComplete", duration))
                val reportGroup = buildReportGroup(
                    server = server,
                    sourceBaseDir = key.sourceBaseDir,
                    remoteBaseDir = key.remoteBaseDir,
                    localPaths = groupItems.map { it.localPath },
                    relativePaths = groupItems.map { it.relativePath },
                    success = true,
                    duration = duration,
                    totalSize = syncResult.totalSize,
                    output = syncResult.output,
                    transferredFileList = syncResult.transferredFileList,
                    backupPath = backupPath
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
                val result = DeployResult(false, taskId = taskId, error = DeployXBundle.message("deploy.error.deployException", e.message ?: ""), duration = System.currentTimeMillis() - startTime)
                results.add(result)
                groupLog(DeployXBundle.message("deploy.log.errorLog", result.error ?: ""))
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
        logCallback?.invoke(DeployXBundle.message("deploy.log.deployTaskHeader", taskId))
        logCallback?.invoke(DeployXBundle.message("deploy.log.localPath", request.localPath))
        logCallback?.invoke(DeployXBundle.message("deploy.log.remotePath", request.serverId, request.remotePath))

        val server = ServerManager.getInstance().getServer(request.serverId)
            ?: return DeployResult(
                success = false,
                taskId = taskId,
                error = DeployXBundle.message("deploy.error.serverNotFound", request.serverId),
                logs = listOf(DeployXBundle.message("deploy.log.serverNotFoundLog", request.serverId))
            )

        // 判断是否需要 SSH 连接
        val needsSshConnection = !request.preCommand.isNullOrBlank() ||
                !request.backupDir.isNullOrBlank() ||
                !request.unzipDest.isNullOrBlank() ||
                !request.postCommand.isNullOrBlank()

        // 先建立 SSH 连接（如果需要），确保连接可用后再上传
        val sshConnection = if (needsSshConnection) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.connectingServer", server.displayAddress))
            val conn = SshConnection(server)
            val connectResult = conn.connectWithDetails()
            if (!connectResult.success) {
                val errorDetail = if (connectResult.exceptionClass != null) " (${connectResult.exceptionClass})" else ""
                logCallback?.invoke(DeployXBundle.message("deploy.log.cannotConnectToServerError", "${server.displayAddress}$errorDetail"))
                if (connectResult.errorMessage != null) {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.detail", connectResult.errorMessage))
                }
                return DeployResult(
                    success = false,
                    taskId = taskId,
                    error = DeployXBundle.message("deploy.error.cannotConnectToServer", "${server.displayAddress}$errorDetail"),
                    logs = listOfNotNull(
                        DeployXBundle.message("deploy.log.cannotConnectServerLog"),
                        connectResult.errorMessage?.let { DeployXBundle.message("deploy.log.detailLog", it) }
                    )
                )
            }
            logCallback?.invoke(DeployXBundle.message("deploy.log.sshConnected"))
            conn
        } else null

        try {
            // 步骤 1: 执行上传前命令（在备份和上传之前执行）
            if (!request.preCommand.isNullOrBlank()) {
                val resolvedPreCommand = ScriptRefResolver.resolve(request.preCommand, server, request.remotePath)
                logCallback?.invoke(DeployXBundle.message("deploy.log.preCommand", resolvedPreCommand))
                val conn = requireNotNull(sshConnection) { "SSH connection should not be null at this point" }
                val preResult = conn.executeCommand(resolvedPreCommand)
                if (preResult.output.isNotBlank()) {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.preOutput", preResult.output.trim()))
                }
                if (!preResult.success) {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.preCommandFailedExit", preResult.exitCode, preResult.error))
                } else {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.preCommandSuccess"))
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
                val backupDir = requireNotNull(request.backupDir) { "backupDir should not be null at this point" }
                logCallback?.invoke(DeployXBundle.message("deploy.log.backupSingle", if (needsSshConnection) 4 else 2, backupSource, backupDir))
                val backupResult = doBackup(sshConnection, backupSource, backupDir, logCallback)
                if (backupResult.success) {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.backupSuccess", backupResult.path ?: ""))
                    backupPath = backupResult.path
                } else {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.backupFailedErrorLog", backupResult.error ?: ""))
                    return DeployResult(
                        success = false,
                        taskId = taskId,
                        backupPath = backupResult.path,
                        error = DeployXBundle.message("deploy.error.backupFailed", backupResult.error ?: ""),
                        logs = listOf(DeployXBundle.message("deploy.error.backupFailed", backupResult.error ?: "")),
                        duration = System.currentTimeMillis() - startTime
                    )
                }
            } else {
                logCallback?.invoke(DeployXBundle.message("deploy.log.skipBackup", if (needsSshConnection) 4 else 2))
            }

            // 步骤 3: 上传文件（使用 rsync 命令）
            val uploadStep = if (needsSshConnection) 2 else 1
            logCallback?.invoke(DeployXBundle.message("deploy.log.uploadFiles", uploadStep, if (needsSshConnection) 4 else 2))
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
                logCallback?.invoke(DeployXBundle.message("deploy.log.uploadFailedErrorLog", syncResult.error ?: ""))
                saveHistory(request, syncResult, startTime, HistoryRecord.OperationStatus.FAILED)
                return DeployResult(
                    success = false,
                    taskId = taskId,
                    backupPath = backupPath,
                    error = DeployXBundle.message("deploy.error.uploadFailed", syncResult.error ?: ""),
                    logs = listOf(DeployXBundle.message("deploy.error.uploadFailed", syncResult.error ?: "")),
                    duration = System.currentTimeMillis() - startTime
                )
            }
            logCallback?.invoke(DeployXBundle.message("deploy.log.uploadComplete"))

            // 如果不需要 SSH 连接，直接完成
            if (!needsSshConnection) {
                val duration = System.currentTimeMillis() - startTime
                logCallback?.invoke(DeployXBundle.message("deploy.log.deployComplete", duration))
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
                    output = syncResult.output,
                    transferredFileList = syncResult.transferredFileList
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
                logCallback?.invoke(DeployXBundle.message("deploy.log.unzipRemoteFileStart"))
                val filename = File(request.localPath).name
                val remoteFile = "${request.remotePath}/$filename"
                val conn = requireNotNull(sshConnection) { "SSH connection should not be null at this point" }
                val unzipResult = doUnzip(conn, remoteFile, request.unzipDest)

                if (unzipResult.success) {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.unzipSuccess", request.unzipDest))
                } else {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.unzipFailedErrorLog", unzipResult.error ?: ""))
                    saveHistory(request, syncResult, startTime, HistoryRecord.OperationStatus.FAILED)
                    return DeployResult(
                        success = false,
                        taskId = taskId,
                        backupPath = backupPath,
                        transferredFiles = syncResult.transferredFiles,
                        totalSize = syncResult.totalSize,
                        error = DeployXBundle.message("deploy.error.unzipFailed", unzipResult.error ?: ""),
                        duration = System.currentTimeMillis() - startTime
                    )
                }
            } else {
                logCallback?.invoke(DeployXBundle.message("deploy.log.skipUnzipNoDest"))
            }

            // 步骤 5: 执行上传后命令（可选）
            if (!request.postCommand.isNullOrBlank()) {
                val resolvedPostCommand = ScriptRefResolver.resolve(request.postCommand, server, request.remotePath)
                logCallback?.invoke(DeployXBundle.message("deploy.log.postCommand", resolvedPostCommand))
                val conn = requireNotNull(sshConnection) { "SSH connection should not be null at this point" }
                val postResult = conn.executeCommand(resolvedPostCommand)
                if (postResult.output.isNotBlank()) {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.postOutput", postResult.output.trim()))
                }
                if (!postResult.success) {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.postCommandFailedExit", postResult.exitCode, postResult.error))
                } else {
                    logCallback?.invoke(DeployXBundle.message("deploy.log.postCommandSuccess"))
                }
            }

            val duration = System.currentTimeMillis() - startTime
            logCallback?.invoke(DeployXBundle.message("deploy.log.deployComplete", duration))
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
                output = syncResult.output,
                transferredFileList = syncResult.transferredFileList,
                backupPath = backupPath
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
            logCallback?.invoke(DeployXBundle.message("deploy.log.deployExceptionErrorLog", e.message ?: ""))
            return DeployResult(
                success = false,
                taskId = taskId,
                error = DeployXBundle.message("deploy.error.deployException", e.message ?: ""),
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
            logCallback?.invoke(DeployXBundle.message("deploy.log.noMappingErrorLog"))
            return DeployResult(
                success = false,
                error = DeployXBundle.message("deploy.error.noMapping")
            )
        }

        val resolvedMapping = if (serverId != null) {
            resolvedMappings.firstOrNull { it.mapping.serverId == serverId } ?: resolvedMappings.first()
        } else {
            resolvedMappings.first()
        }
        val mapping = resolvedMapping.mapping

        val targetServerId = serverId ?: mapping.serverId
        logCallback?.invoke(DeployXBundle.message("deploy.log.matchedMapping", mapping.name, targetServerId, resolvedMapping.resolvedRemoteDir))

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
        logCallback?.invoke(DeployXBundle.message("deploy.log.redeployHeader"))
        logCallback?.invoke(DeployXBundle.message("deploy.log.originalOperation", record.summary))
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
        output: String,
        transferredFileList: List<String> = emptyList(),
        backupPath: String? = null
    ): UpdateReportGroup {
        val normalizedRelativePaths = relativePaths.map { it.trim('/') }.filter { it.isNotBlank() }
        // transferredFiles 只包含 rsync 实际传输的文件，不回退到选中的路径。
        // 增量同步跳过所有文件（0 transferred）时该列表为空，报告据此显示"无文件变更"，
        // 避免把选中的目录或未变更的文件误展示为"实际更新的文件"。
        val transferredFiles = transferredFileList.map { joinRemotePath(remoteBaseDir, it) }
        return UpdateReportGroup(
            serverId = server.id,
            serverName = server.name,
            serverAddress = server.displayAddress,
            sourceBaseDir = sourceBaseDir,
            remoteBaseDir = remoteBaseDir,
            selectedLocalPaths = localPaths,
            relativePaths = normalizedRelativePaths,
            remotePaths = normalizedRelativePaths.map { joinRemotePath(remoteBaseDir, it) },
            transferredFiles = transferredFiles,
            success = success,
            duration = duration,
            totalSize = totalSize,
            rsyncOutput = output,
            backupPath = backupPath
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
                output = syncResult.output,
                transferredFileList = syncResult.transferredFileList,
                backupPath = key.backupDir
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
                output = syncResult.output,
                transferredFileList = syncResult.transferredFileList,
                backupPath = request.backupDir
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
            logCallback?.invoke(DeployXBundle.message("deploy.log.skipBackupFirstDeploy"))
            return BackupResult(true, null)
        }

        logCallback?.invoke(DeployXBundle.message("deploy.log.compressingBackup", existingPaths.size, backupFile))
        existingPaths.forEach { logCallback?.invoke("  $it") }
        val quotedPaths = existingPaths.joinToString(" ") { shellQuote(it) }
        val result = sshConnection.executeCommand(
            "tar -czf ${shellQuote(backupFile)} -C ${shellQuote(remoteBaseDir)} $quotedPaths"
        )
        return if (result.success) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupCompleteFile", backupFile))
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
            logCallback?.invoke(DeployXBundle.message("deploy.log.skipBackupSourceNotFound", backupSource))
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
            logCallback?.invoke(DeployXBundle.message("deploy.log.archiveBackup", backupSource, backupFile))
            sshConnection.executeCommand("cp ${shellQuote(backupSource)} ${shellQuote(backupFile)}")
        } else {
            // 目录或非压缩文件：压缩为 tar.gz
            val tarName = "${sourceName}_${timestamp}.tar.gz"
            val tarPath = "$backupDir/$tarName"
            val sourceParent = File(backupSource).parent
            logCallback?.invoke(DeployXBundle.message("deploy.log.compressBackup", backupSource, tarPath))
            sshConnection.executeCommand(
                "tar -czf ${shellQuote(tarPath)} -C ${shellQuote(sourceParent ?: ".")} ${shellQuote(sourceName)}"
            )
        }

        return if (backupResult.success) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupComplete"))
            BackupResult(true, backupDir)
        } else {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupFailedWarn", backupResult.error ?: ""))
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
            return UnzipResult(false, error = DeployXBundle.message("deploy.error.fileNotFound", zipPath))
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
