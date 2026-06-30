package com.alianga.idea.deploy.ssh

import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.SyncOptions
import com.alianga.idea.deploy.model.SyncResult
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * rsync 命令封装 - 基于系统 rsync 实现增量同步
 */
class RsyncWrapper {

    companion object {
        private val LOG = Logger.getInstance(RsyncWrapper::class.java)

        /**
         * 检查系统是否安装了 rsync
         */
        fun isRsyncAvailable(): Boolean {
            return try {
                val rsyncPath = FileSyncSettings.getInstance().rsyncPath.ifEmpty { "rsync" }
                val process = ProcessBuilder(rsyncPath, "--version")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 检查 sshpass 是否可用
         */
        fun isSshpassAvailable(): Boolean {
            return try {
                val process = ProcessBuilder("sshpass", "-V")
                    .redirectErrorStream(true)
                    .start()
                process.waitFor() == 0
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * 执行 rsync 同步
     * @param logCallback 实时日志回调，每行输出都会调用
     * @param progressCallback 进度回调，解析到进度信息时调用
     */
    fun sync(
        localPath: String,
        remotePath: String,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((SyncProgress) -> Unit)? = null
    ): SyncResult {
        val startTime = System.currentTimeMillis()

        try {
            // 验证本地路径
            val localFile = File(localPath)
            if (!localFile.exists()) {
                val msg = "本地路径不存在: $localPath"
                logCallback?.invoke("[ERROR] $msg")
                return SyncResult(false, error = msg)
            }

            // 构建 rsync 命令
            val cmd = buildRsyncCommand(localPath, remotePath, serverConfig, options)
            val cmdStr = maskPassword(cmd).joinToString(" ")
            LOG.info("Executing rsync: ${maskPassword(cmd).joinToString(" ")}")
            logCallback?.invoke("[CMD] $cmdStr")

            // 执行命令
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val outputBuilder = StringBuilder()
            var transferredFiles = 0
            var totalSize = 0L

            // 读取输出并解析进度
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                outputBuilder.appendLine(currentLine)

                // 实时日志回调 - 每行输出都通知
                logCallback?.invoke(currentLine)

                // 解析进度信息
                val progress = parseProgressLine(currentLine)
                if (progress != null) {
                    progressCallback?.invoke(progress)
                }

                // 统计传输文件
                if (currentLine.contains("sent ") && currentLine.contains("received ") &&
                    currentLine.contains("bytes")
                ) {
                    val sentMatch = Regex("sent (\\d+) bytes").find(currentLine)
                    val receivedMatch = Regex("received (\\d+) bytes").find(currentLine)
                    if (sentMatch != null && receivedMatch != null) {
                        totalSize = (sentMatch.groupValues[1].toLongOrNull() ?: 0L) +
                                (receivedMatch.groupValues[1].toLongOrNull() ?: 0L)
                    }
                }
            }

            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime

            return if (exitCode == 0) {
                logCallback?.invoke("[OK] rsync 完成 (exit code: 0, 耗时: ${duration}ms)")
                SyncResult(
                    success = true,
                    transferredFiles = transferredFiles,
                    totalSize = totalSize,
                    duration = duration,
                    output = outputBuilder.toString()
                )
            } else {
                val errMsg = "rsync 执行失败 (exit code: $exitCode)"
                logCallback?.invoke("[ERROR] $errMsg")
                SyncResult(
                    success = false,
                    duration = duration,
                    error = errMsg,
                    output = outputBuilder.toString()
                )
            }
        } catch (e: Exception) {
            LOG.error("rsync execution failed", e)
            val errMsg = "rsync 执行异常: ${e.message}"
            logCallback?.invoke("[ERROR] $errMsg")
            return SyncResult(
                success = false,
                duration = System.currentTimeMillis() - startTime,
                error = errMsg
            )
        }
    }

    /**
     * 使用 rsync --files-from 执行批量上传。
     * relativePaths 必须是 sourceBaseDir 下的相对路径；目录项建议以 / 结尾。
     */
    fun syncFilesFrom(
        sourceBaseDir: String,
        remoteBaseDir: String,
        relativePaths: List<String>,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((SyncProgress) -> Unit)? = null
    ): SyncResult {
        val startTime = System.currentTimeMillis()
        if (relativePaths.isEmpty()) {
            return SyncResult(false, error = "files-from 列表为空")
        }

        try {
            val sourceBase = sourceBaseDir.trimEnd('/') + "/"
            val remoteBase = remoteBaseDir.trimEnd('/') + "/"
            val baseDir = File(sourceBase)
            if (!baseDir.exists()) {
                val msg = "本地映射根目录不存在: $sourceBase"
                logCallback?.invoke("[ERROR] $msg")
                return SyncResult(false, error = msg)
            }

            val cmd = buildRsyncFilesFromCommand(sourceBase, remoteBase, serverConfig, options)
            val cmdStr = maskPassword(cmd).joinToString(" ")
            LOG.info("Executing rsync files-from: $cmdStr")
            logCallback?.invoke("[CMD] $cmdStr")
            logCallback?.invoke("[FILES-FROM]")
            relativePaths.forEach { logCallback?.invoke("  $it") }

            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            process.outputStream.use { output ->
                relativePaths.forEach { path ->
                    output.write(path.toByteArray(StandardCharsets.UTF_8))
                    output.write(0)
                }
                output.flush()
            }

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val outputBuilder = StringBuilder()
            var totalSize = 0L
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                outputBuilder.appendLine(currentLine)
                logCallback?.invoke(currentLine)

                val progress = parseProgressLine(currentLine)
                if (progress != null) {
                    progressCallback?.invoke(progress)
                }

                if (currentLine.contains("sent ") && currentLine.contains("received ") && currentLine.contains("bytes")) {
                    val sentMatch = Regex("sent (\\d+) bytes").find(currentLine)
                    val receivedMatch = Regex("received (\\d+) bytes").find(currentLine)
                    if (sentMatch != null && receivedMatch != null) {
                        totalSize = (sentMatch.groupValues[1].toLongOrNull() ?: 0L) +
                                (receivedMatch.groupValues[1].toLongOrNull() ?: 0L)
                    }
                }
            }

            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime
            return if (exitCode == 0) {
                logCallback?.invoke("[OK] rsync files-from 完成 (exit code: 0, 耗时: ${duration}ms)")
                SyncResult(
                    success = true,
                    transferredFiles = relativePaths.size,
                    totalSize = totalSize,
                    duration = duration,
                    output = outputBuilder.toString()
                )
            } else {
                val errMsg = "rsync files-from 执行失败 (exit code: $exitCode)"
                logCallback?.invoke("[ERROR] $errMsg")
                SyncResult(false, duration = duration, error = errMsg, output = outputBuilder.toString())
            }
        } catch (e: Exception) {
            LOG.error("rsync files-from execution failed", e)
            val errMsg = "rsync files-from 执行异常: ${e.message}"
            logCallback?.invoke("[ERROR] $errMsg")
            return SyncResult(false, duration = System.currentTimeMillis() - startTime, error = errMsg)
        }
    }

    /**
     * 构建 rsync 命令参数
     */
    private fun buildRsyncCommand(
        localPath: String,
        remotePath: String,
        serverConfig: ServerConfig,
        options: SyncOptions
    ): List<String> {
        val settings = FileSyncSettings.getInstance()
        val rsyncPath = settings.rsyncPath.ifEmpty { "rsync" }

        // 如果服务器使用密码认证且 sshpass 可用，用 sshpass 包装
        val useSshpass = serverConfig.authType == ServerConfig.AuthType.PASSWORD &&
                serverConfig.password.isNotEmpty() &&
                settings.sshpassAvailable

        val cmd = mutableListOf<String>()

        if (useSshpass) {
            cmd.add("sshpass")
            cmd.add("-p")
            cmd.add(serverConfig.password)
        }

        cmd.add(rsyncPath)

        // 从设置读取 rsync 选项（用户可自定义）
        val userOptions = settings.rsyncOptions.trim()
        if (userOptions.isNotEmpty()) {
            // 解析用户自定义选项
            cmd.addAll(userOptions.split("\\s+".toRegex()).filter { it.isNotEmpty() })
        } else {
            // 默认选项
            cmd.add("-avz")
            if (settings.showProgress) {
                cmd.add("--progress")
            }
            cmd.add("--stats")
        }

        // SSH 配置
        val sshOpts = buildSshOptions(serverConfig)
        cmd.add("-e")
        cmd.add(sshOpts)

        // 排除规则
        for (pattern in options.excludePatterns) {
            cmd.add("--exclude=$pattern")
        }

        // 干跑模式
        if (options.dryRun) {
            cmd.add("--dry-run")
        }

        // 删除远程多余文件
        if (options.deleteRemote) {
            cmd.add("--delete")
        }

        // 源路径（注意：rsync 中 /path/dir 表示上传目录本身，/path/dir/ 表示只上传目录内容）
        val source = localPath.trimEnd('/')
        cmd.add(source)

        // 目标路径
        cmd.add("${serverConfig.user}@${serverConfig.host}:$remotePath")

        return cmd
    }

    /**
     * 构建 --files-from 模式的 rsync 命令参数
     */
    private fun buildRsyncFilesFromCommand(
        sourceBaseDir: String,
        remoteBaseDir: String,
        serverConfig: ServerConfig,
        options: SyncOptions
    ): List<String> {
        val settings = FileSyncSettings.getInstance()
        val rsyncPath = settings.rsyncPath.ifEmpty { "rsync" }
        val useSshpass = serverConfig.authType == ServerConfig.AuthType.PASSWORD &&
                serverConfig.password.isNotEmpty() &&
                settings.sshpassAvailable

        val cmd = mutableListOf<String>()
        if (useSshpass) {
            cmd.add("sshpass")
            cmd.add("-p")
            cmd.add(serverConfig.password)
        }
        cmd.add(rsyncPath)

        val userOptions = settings.rsyncOptions.trim()
        if (userOptions.isNotEmpty()) {
            cmd.addAll(userOptions.split("\\s+".toRegex()).filter { it.isNotEmpty() })
        } else {
            cmd.add("-avz")
            if (settings.showProgress) cmd.add("--progress")
            cmd.add("--stats")
        }

        // files-from 模式显式启用递归，并用 NUL 分隔路径
        if (!hasRecursiveOption(cmd)) {
            cmd.add("-r")
        }
        cmd.add("--files-from=-")
        cmd.add("--from0")

        val sshOpts = buildSshOptions(serverConfig)
        cmd.add("-e")
        cmd.add(sshOpts)

        for (pattern in options.excludePatterns) {
            cmd.add("--exclude=$pattern")
        }
        if (options.dryRun) cmd.add("--dry-run")
        if (options.deleteRemote) cmd.add("--delete")

        cmd.add(sourceBaseDir.trimEnd('/') + "/")
        cmd.add("${serverConfig.user}@${serverConfig.host}:${remoteBaseDir.trimEnd('/')}/")
        return cmd
    }

    private fun hasRecursiveOption(cmd: List<String>): Boolean {
        return cmd.any { arg ->
            arg == "-r" || arg == "--recursive" ||
                    (arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).contains('r')) ||
                    (arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).contains('a'))
        }
    }

    /**
     * 构建 SSH 选项字符串
     * 密码认证时返回 ssh 命令（sshpass 已在外层处理）
     * 密钥认证时添加 -i 参数
     */
    private fun buildSshOptions(serverConfig: ServerConfig): String {
        val opts = StringBuilder("ssh -o StrictHostKeyChecking=no -o ConnectTimeout=")
        opts.append(FileSyncSettings.getInstance().connectTimeout / 1000) // 转为秒

        if (serverConfig.port != 22) {
            opts.append(" -p ${serverConfig.port}")
        }

        if (serverConfig.authType == ServerConfig.AuthType.KEY && serverConfig.keyFile.isNotEmpty()) {
            opts.append(" -i ${serverConfig.keyFile}")
        }

        return opts.toString()
    }

    /**
     * 遮蔽命令中的密码信息，用于日志输出
     * sshpass -p <password> → sshpass -p ****
     */
    private fun maskPassword(cmd: List<String>): List<String> {
        val masked = cmd.toMutableList()
        for (i in masked.indices) {
            if (masked[i] == "-p" && i > 0 && masked[i - 1].contains("sshpass") && i + 1 < masked.size) {
                masked[i + 1] = "****"
            }
        }
        return masked
    }

    /**
     * 解析 rsync 输出中的进度信息
     * 支持多种 rsync 进度输出格式
     */
    private fun parseProgressLine(line: String): SyncProgress? {
        // 格式1: "filename  1,234,567  45%  1.23MB/s  0:00:03"
        val progressRegex1 = Regex("""(.+?)\s+(\d[\d,]*)\s+(\d+)%\s+([\d.]+\w+/s)\s+(\d+:\d+:\d+)""")
        val match1 = progressRegex1.find(line.trim())
        if (match1 != null) {
            return SyncProgress(
                currentFile = match1.groupValues[1].trim(),
                transferredBytes = match1.groupValues[2].replace(",", "").toLongOrNull() ?: 0,
                percentage = match1.groupValues[3].toIntOrNull() ?: 0,
                speed = match1.groupValues[4],
                eta = match1.groupValues[5]
            )
        }

        // 格式2: "filename  1,234,567  45%  1.23MB/s"
        val progressRegex2 = Regex("""(.+?)\s+(\d[\d,]*)\s+(\d+)%\s+([\d.]+\w+/s)""")
        val match2 = progressRegex2.find(line.trim())
        if (match2 != null) {
            return SyncProgress(
                currentFile = match2.groupValues[1].trim(),
                transferredBytes = match2.groupValues[2].replace(",", "").toLongOrNull() ?: 0,
                percentage = match2.groupValues[3].toIntOrNull() ?: 0,
                speed = match2.groupValues[4]
            )
        }

        return null
    }

    /**
     * 同步进度信息
     */
    data class SyncProgress(
        val currentFile: String = "",
        val transferredBytes: Long = 0,
        val percentage: Int = 0,
        val speed: String = "",
        val eta: String = ""
    )
}
