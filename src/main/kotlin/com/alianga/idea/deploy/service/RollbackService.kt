package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.RollbackRecord
import com.alianga.idea.deploy.model.RollbackResult
import com.alianga.idea.deploy.ssh.SshConnection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.io.ByteArrayOutputStream

/**
 * 回滚服务 - 负责执行部署回滚操作
 *
 * 功能：
 * 1. 检查历史记录是否可回滚
 * 2. 验证备份文件存在
 * 3. 执行 tar 解压恢复
 * 4. 记录回滚历史
 */
class RollbackService {

    companion object {
        private val LOG = Logger.getInstance(RollbackService::class.java)

        fun getInstance(): RollbackService =
            ApplicationManager.getApplication().getService(RollbackService::class.java)
    }

    /**
     * 检查一条历史记录是否可以回滚
     *
     * 可回滚条件：
     * 1. 记录类型为 DEPLOY 类型
     * 2. canRollback 标记为 true
     * 3. backupFilePath 不为空
     * 4. 服务器配置存在
     */
    fun canRollback(record: HistoryRecord): Boolean {
        if (record.type != HistoryRecord.OperationType.DEPLOY) return false
        if (!record.canRollback) return false
        if (record.backupFilePath.isBlank()) return false

        val server = ServerManager.getInstance().getServer(record.serverId) ?: return false
        return true
    }

    /**
     * 获取备份文件中的内容列表（用于确认对话框预览）
     */
    fun getBackupFileList(
        record: HistoryRecord,
        logCallback: ((String) -> Unit)? = null
    ): List<String> {
        val server = ServerManager.getInstance().getServer(record.serverId)
            ?: return emptyList()

        logCallback?.invoke(DeployXBundle.message("rollback.log.checkingBackupFile"))

        val connection = SshConnection(server)
        if (!connection.connect()) {
            logCallback?.invoke(DeployXBundle.message("rollback.error.connectFailed"))
            return emptyList()
        }

        return try {
            // 检查备份文件是否存在
            val checkResult = connection.executeCommand("test -f '${shellEscape(record.backupFilePath)}'")
            if (!checkResult.success) {
                logCallback?.invoke(DeployXBundle.message("rollback.error.backupNotFound", record.backupFilePath))
                return emptyList()
            }

            logCallback?.invoke(DeployXBundle.message("rollback.listingBackupContents"))

            // 列出 tar.gz 中的文件
            val listResult = connection.executeCommand("tar -tzf '${shellEscape(record.backupFilePath)}' 2>/dev/null | head -100")
            if (!listResult.success) {
                logCallback?.invoke(DeployXBundle.message("rollback.error.listFailed", listResult.error ?: ""))
                return emptyList()
            }

            listResult.output.lines().filter { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 执行回滚
     *
     * @param record 要回滚的历史记录
     * @param logCallback 日志回调
     * @param progressCallback 进度回调 (progress 0-100, message)
     */
    fun rollback(
        record: HistoryRecord,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((progress: Int, message: String) -> Unit)? = null
    ): RollbackResult {
        val startTime = System.currentTimeMillis()
        val logs = mutableListOf<String>()

        fun log(message: String) {
            LOG.info(message)
            logs.add(message)
            logCallback?.invoke(message)
        }

        log(DeployXBundle.message("rollback.log.starting"))
        progressCallback?.invoke(5, DeployXBundle.message("rollback.progress.validating"))

        // 1. 验证服务器配置
        val server = ServerManager.getInstance().getServer(record.serverId)
        if (server == null) {
            val error = DeployXBundle.message("rollback.error.serverNotFound", record.serverId)
            log(error)
            return RollbackResult(
                success = false,
                error = error,
                duration = System.currentTimeMillis() - startTime,
                logs = logs
            )
        }

        log(DeployXBundle.message("rollback.log.serverInfo", server.displayAddress))
        log(DeployXBundle.message("rollback.log.targetPath", record.targetPath))
        log(DeployXBundle.message("rollback.log.backupFile", record.backupFilePath))

        progressCallback?.invoke(10, DeployXBundle.message("rollback.progress.connecting"))

        // 2. 建立 SSH 连接
        val connection = SshConnection(server)
        if (!connection.connect()) {
            val error = DeployXBundle.message("rollback.error.connectFailed")
            log(error)
            return RollbackResult(
                success = false,
                error = error,
                duration = System.currentTimeMillis() - startTime,
                logs = logs
            )
        }

        return try {
            progressCallback?.invoke(20, DeployXBundle.message("rollback.progress.checkingBackup"))

            // 3. 验证备份文件存在
            val checkResult = connection.executeCommand("test -f '${shellEscape(record.backupFilePath)}'")
            if (!checkResult.success) {
                val error = DeployXBundle.message("rollback.error.backupNotFound", record.backupFilePath)
                log(error)
                RollbackResult(
                    success = false,
                    error = error,
                    duration = System.currentTimeMillis() - startTime,
                    logs = logs
                )
            } else {
                log(DeployXBundle.message("rollback.log.backupVerified"))
                progressCallback?.invoke(30, DeployXBundle.message("rollback.progress.listingFiles"))

                // 4. 获取备份文件列表（用于报告）
                val listResult = connection.executeCommand("tar -tzf '${shellEscape(record.backupFilePath)}' 2>/dev/null")
                val fileList = if (listResult.success) {
                    listResult.output.lines().filter { it.isNotBlank() }
                } else {
                    emptyList()
                }

                log(DeployXBundle.message("rollback.log.fileCount", fileList.size))
                progressCallback?.invoke(50, DeployXBundle.message("rollback.progress.extracting"))

                // 5. 执行解压恢复
                log(DeployXBundle.message("rollback.log.extracting"))
                val extractResult = connection.executeCommand(
                    "tar -xzf '${shellEscape(record.backupFilePath)}' -C '${shellEscape(record.targetPath)}' 2>&1"
                )

                if (!extractResult.success) {
                    val error = DeployXBundle.message(
                        "rollback.error.extractFailed",
                        extractResult.error ?: extractResult.output
                    )
                    log(error)
                    RollbackResult(
                        success = false,
                        error = error,
                        duration = System.currentTimeMillis() - startTime,
                        logs = logs
                    )
                } else {
                    progressCallback?.invoke(90, DeployXBundle.message("rollback.progress.recording"))
                    log(DeployXBundle.message("rollback.log.extractSuccess"))

                    val duration = System.currentTimeMillis() - startTime

                    // 6. 生成报告
                    val report = generateRollbackReport(record, server, fileList, duration, logs)

                    // 7. 记录回滚历史
                    val rollbackRecord = RollbackRecord(
                        sourceDeployId = record.id,
                        serverId = record.serverId,
                        serverName = record.serverName,
                        serverAddress = record.serverAddress,
                        remoteTargetPath = record.targetPath,
                        backupFileUsed = record.backupFilePath,
                        rolledBackFiles = fileList,
                        status = HistoryRecord.OperationStatus.SUCCESS,
                        duration = duration,
                        reportText = report
                    )
                    HistoryManager.getInstance().addRollbackRecord(rollbackRecord)

                    progressCallback?.invoke(100, DeployXBundle.message("rollback.progress.completed"))
                    log(DeployXBundle.message("rollback.log.completed", duration))

                    RollbackResult(
                        success = true,
                        recordId = rollbackRecord.id,
                        rolledBackFiles = fileList,
                        duration = duration,
                        logs = logs,
                        reportText = report
                    )
                }
            }
        } catch (e: Exception) {
            val error = DeployXBundle.message("rollback.error.exception", e.message ?: "")
            log(error)
            RollbackResult(
                success = false,
                error = error,
                duration = System.currentTimeMillis() - startTime,
                logs = logs
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 生成回滚报告
     */
    private fun generateRollbackReport(
        record: HistoryRecord,
        server: com.alianga.idea.deploy.model.ServerConfig,
        fileList: List<String>,
        duration: Long,
        logs: List<String>
    ): String {
        val output = StringBuilder()
        output.append("# ").append(DeployXBundle.message("rollback.report.title")).append("\n\n")

        output.append("## ").append(DeployXBundle.message("rollback.report.basicInfo")).append("\n\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.operationType")).append("**: ROLLBACK\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.executionTime")).append("**: ").append(record.formattedDate).append("\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.duration")).append("**: ").append("${duration}ms\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.status")).append("**: ✅ ").append(DeployXBundle.message("rollback.report.statusSuccess")).append("\n\n")

        output.append("## ").append(DeployXBundle.message("rollback.report.details")).append("\n\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.sourceDeploy")).append("**: ").append(record.id).append("\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.server")).append("**: ").append(record.serverName).append(" (").append(server.displayAddress).append(")\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.remoteTargetPath")).append("**: ").append(record.targetPath).append("\n")
        output.append("- **").append(DeployXBundle.message("rollback.report.backupFileUsed")).append("**: ").append(record.backupFilePath).append("\n\n")

        output.append("## ").append(DeployXBundle.message("rollback.report.restoredFiles")).append(" (").append(fileList.size).append(")\n\n")
        if (fileList.isEmpty()) {
            output.append("- *").append(DeployXBundle.message("rollback.report.noFiles")).append("*\n")
        } else {
            fileList.take(50).forEach { file ->
                output.append("- ").append(file).append("\n")
            }
            if (fileList.size > 50) {
                output.append("- ... ").append(DeployXBundle.message("rollback.report.moreFiles", fileList.size - 50)).append("\n")
            }
        }

        output.append("\n## ").append(DeployXBundle.message("rollback.report.executionLog")).append("\n\n")
        output.append("```\n")
        logs.takeLast(100).forEach { line ->
            output.append(line).append("\n")
        }
        output.append("```\n")

        return output.toString()
    }

    /**
     * Shell 路径转义，防止路径中的特殊字符（如空格、单引号等导致命令执行失败
     */
    private fun shellEscape(path: String): String {
        return path.replace("'", "'\\''")
    }
}
