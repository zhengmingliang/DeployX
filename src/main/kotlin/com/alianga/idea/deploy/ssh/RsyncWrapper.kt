package com.alianga.idea.deploy.ssh

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.SyncDirection
import com.alianga.idea.deploy.model.SyncOptions
import com.alianga.idea.deploy.model.SyncResult
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.*

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

        val auth = prepareAuthContext(serverConfig)
        try {
            // 验证本地路径
            val localFile = File(localPath)
            if (!localFile.exists()) {
                val msg = DeployXBundle.message("ssh.rsync.localPathNotFound", localPath)
                logCallback?.invoke("[ERROR] $msg")
                return SyncResult(false, error = msg)
            }

            // 构建 rsync 命令
            val cmd = buildRsyncCommand(localPath, remotePath, serverConfig, options, auth.commandPrefix)
            val cmdStr = maskPassword(cmd).joinToString(" ")
            LOG.info("Executing rsync: ${maskPassword(cmd).joinToString(" ")}")
            logCallback?.invoke("[CMD] $cmdStr")

            // 执行命令
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            auth.environment.forEach { (k, v) -> pb.environment()[k] = v }
            val process = pb.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val outputBuilder = StringBuilder()
            var totalSize = 0L
            val transferredFileList = mutableListOf<String>()

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

                // 收集传输的文件列表（从 rsync -v 输出）
                // 排除进度行（包含 %、MB/s 等）和统计行
                if (isFileTransferLine(currentLine)) {
                    transferredFileList.add(currentLine.trim())
                }

                // 统计传输文件大小
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
                logCallback?.invoke(DeployXBundle.message("ssh.rsync.completed", duration))
                SyncResult(
                    success = true,
                    transferredFiles = transferredFileList.size,
                    transferredFileList = transferredFileList,
                    totalSize = totalSize,
                    duration = duration,
                    output = outputBuilder.toString()
                )
            } else {
                val errMsg = DeployXBundle.message("ssh.rsync.executionFailed", exitCode)
                logCallback?.invoke("[ERROR] $errMsg")
                SyncResult(
                    success = false,
                    transferredFiles = transferredFileList.size,
                    transferredFileList = transferredFileList,
                    duration = duration,
                    error = errMsg,
                    output = outputBuilder.toString()
                )
            }
        } catch (e: Exception) {
            LOG.error("rsync execution failed", e)
            val errMsg = DeployXBundle.message("ssh.rsync.executionException", e.message ?: "")
            logCallback?.invoke("[ERROR] $errMsg")
            return SyncResult(
                success = false,
                duration = System.currentTimeMillis() - startTime,
                error = errMsg
            )
        } finally {
            auth.cleanup()
        }
    }

    /**
     * 从服务器拉取文件到本地（PULL）
     * @param localPath 本地目标路径
     * @param remotePath 远程源路径
     * @param serverConfig 服务器配置
     * @param options 同步选项（direction 将被强制设置为 PULL）
     * @param logCallback 实时日志回调
     * @param progressCallback 进度回调
     */
    fun pull(
        localPath: String,
        remotePath: String,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(direction = SyncDirection.PULL),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((SyncProgress) -> Unit)? = null
    ): SyncResult {
        // 强制使用 PULL 方向
        val pullOptions = options.copy(direction = SyncDirection.PULL)
        val startTime = System.currentTimeMillis()

        val auth = prepareAuthContext(serverConfig)
        try {
            // 确保本地目录存在
            val localDir = File(localPath).parentFile
            if (localDir != null && !localDir.exists()) {
                localDir.mkdirs()
            }

            // 构建并执行 rsync 命令（buildRsyncCommand 会根据方向处理源和目标）
            val cmd = buildRsyncCommand(localPath, remotePath, serverConfig, pullOptions, auth.commandPrefix)
            val cmdStr = maskPassword(cmd).joinToString(" ")
            LOG.info("Executing rsync pull: $cmdStr")
            logCallback?.invoke("[CMD] PULL: $cmdStr")

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            auth.environment.forEach { (k, v) -> pb.environment()[k] = v }
            val process = pb.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val outputBuilder = StringBuilder()
            var totalSize = 0L
            val transferredFileList = mutableListOf<String>()

            // 读取输出并解析进度
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                outputBuilder.appendLine(currentLine)
                logCallback?.invoke(currentLine)

                val progress = parseProgressLine(currentLine)
                if (progress != null) {
                    progressCallback?.invoke(progress)
                }

                // 收集传输的文件列表
                if (isFileTransferLine(currentLine)) {
                    transferredFileList.add(currentLine.trim())
                }

                // 统计传输文件大小
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
                logCallback?.invoke(DeployXBundle.message("ssh.rsync.pullCompleted", duration))
                SyncResult(
                    success = true,
                    transferredFiles = transferredFileList.size,
                    transferredFileList = transferredFileList,
                    totalSize = totalSize,
                    duration = duration,
                    output = outputBuilder.toString()
                )
            } else {
                val errMsg = DeployXBundle.message("ssh.rsync.pullFailed", exitCode)
                logCallback?.invoke("[ERROR] $errMsg")
                SyncResult(
                    success = false,
                    transferredFiles = transferredFileList.size,
                    transferredFileList = transferredFileList,
                    duration = duration,
                    error = errMsg,
                    output = outputBuilder.toString()
                )
            }
        } catch (e: Exception) {
            LOG.error("rsync pull execution failed", e)
            val errMsg = DeployXBundle.message("ssh.rsync.pullException", e.message ?: "")
            logCallback?.invoke("[ERROR] $errMsg")
            return SyncResult(
                success = false,
                duration = System.currentTimeMillis() - startTime,
                error = errMsg
            )
        } finally {
            auth.cleanup()
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
            return SyncResult(false, error = DeployXBundle.message("ssh.rsync.filesFromEmpty"))
        }

        val auth = prepareAuthContext(serverConfig)
        // 临时文件存放要同步的相对路径列表（NUL 分隔，配合 --from0）。
        // 改用 --files-from=FILE 而非 --files-from=- 通过 stdin 传入，
        // 避免 Windows 下与 cygwin rsync 的 stdin 交互出现编码/阻塞问题；
        // 使用完毕后自动删除。
        val filesFromTemp = File.createTempFile("deployx_files_from_", ".txt").apply { deleteOnExit() }
        try {
            filesFromTemp.outputStream().use { output ->
                relativePaths.forEach { path ->
                    output.write(path.toByteArray(StandardCharsets.UTF_8))
                    output.write(0)
                }
                output.flush()
            }

            val sourceBase = sourceBaseDir.trimEnd('/') + "/"
            val remoteBase = remoteBaseDir.trimEnd('/') + "/"
            val baseDir = File(sourceBase)
            if (!baseDir.exists()) {
                val msg = DeployXBundle.message("ssh.rsync.baseDirNotFound", sourceBase)
                logCallback?.invoke("[ERROR] $msg")
                return SyncResult(false, error = msg)
            }

            val cmd = buildRsyncFilesFromCommand(
                sourceBase, remoteBase, serverConfig, options, auth.commandPrefix, filesFromTemp
            )
            val cmdStr = maskPassword(cmd).joinToString(" ")
            LOG.info("Executing rsync files-from: $cmdStr")
            logCallback?.invoke("[CMD] $cmdStr")
            logCallback?.invoke("[FILES-FROM]")
            relativePaths.forEach { logCallback?.invoke("  $it") }

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            auth.environment.forEach { (k, v) -> pb.environment()[k] = v }
            val process = pb.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val outputBuilder = StringBuilder()
            var totalSize = 0L
            val transferredFileList = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                outputBuilder.appendLine(currentLine)
                logCallback?.invoke(currentLine)

                val progress = parseProgressLine(currentLine)
                if (progress != null) {
                    progressCallback?.invoke(progress)
                }

                // 收集传输的文件列表
                if (isFileTransferLine(currentLine)) {
                    transferredFileList.add(currentLine.trim())
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
                logCallback?.invoke(DeployXBundle.message("ssh.rsync.filesFromCompleted", duration))
                SyncResult(
                    success = true,
                    transferredFiles = transferredFileList.size,
                    transferredFileList = transferredFileList,
                    totalSize = totalSize,
                    duration = duration,
                    output = outputBuilder.toString()
                )
            } else {
                val errMsg = DeployXBundle.message("ssh.rsync.filesFromFailed", exitCode)
                logCallback?.invoke("[ERROR] $errMsg")
                SyncResult(false, transferredFiles = transferredFileList.size, transferredFileList = transferredFileList, duration = duration, error = errMsg, output = outputBuilder.toString())
            }
        } catch (e: Exception) {
            LOG.error("rsync files-from execution failed", e)
            val errMsg = DeployXBundle.message("ssh.rsync.filesFromException", e.message ?: "")
            logCallback?.invoke("[ERROR] $errMsg")
            return SyncResult(false, duration = System.currentTimeMillis() - startTime, error = errMsg)
        } finally {
            runCatching { filesFromTemp.delete() }
            auth.cleanup()
        }
    }

    /**
     * 使用 rsync --files-from 执行批量拉取（从远程到本地）。
     *
     * relativePaths 中：
     * - 非空项：remoteBaseDir 下的相对路径，使用 --files-from 精确拉取；
     * - 空字符串项：表示“映射根目录 / 整目录”（例如直接对映射根目录或目录发起拉取）。
     *   此时不再走 --files-from（空条目会让 rsync 实际传输 0 个文件），而是执行一次
     *   整目录递归拉取——rsync 会自动只传输与本地存在差异的文件，满足“增量拉取差异文件”的诉求。
     */
    fun pullFilesFrom(
        localBaseDir: String,
        remoteBaseDir: String,
        relativePaths: List<String>,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(direction = SyncDirection.PULL),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((SyncProgress) -> Unit)? = null
    ): SyncResult {
        // 强制使用 PULL 方向
        val pullOptions = options.copy(direction = SyncDirection.PULL)
        if (relativePaths.isEmpty()) {
            return SyncResult(false, error = DeployXBundle.message("ssh.rsync.filesFromEmpty"))
        }

        // 拆分：空相对路径（目录 / 映射根）→ 整目录递归拉取；非空 → --files-from 精确拉取
        val (dirPaths, filePaths) = relativePaths.partition { it.isBlank() }

        // 纯整目录拉取
        if (filePaths.isEmpty()) {
            return pullWholeDirectory(localBaseDir, remoteBaseDir, serverConfig, pullOptions, logCallback, progressCallback)
        }

        // 纯精确拉取（与历史行为一致）
        if (dirPaths.isEmpty()) {
            return runPullFilesFrom(localBaseDir, remoteBaseDir, relativePaths, serverConfig, pullOptions, logCallback, progressCallback)
        }

        // 混合：先整目录递归拉取，再对指定文件精确拉取，最后合并结果
        val dirResult = pullWholeDirectory(localBaseDir, remoteBaseDir, serverConfig, pullOptions, logCallback, progressCallback)
        val fileResult = runPullFilesFrom(localBaseDir, remoteBaseDir, filePaths, serverConfig, pullOptions, logCallback, progressCallback)
        return mergeSyncResults(listOf(dirResult, fileResult), logCallback)
    }

    /**
     * 整目录递归拉取（从远程到本地）：直接用普通 rsync 递归传输整个映射根目录，
     * rsync 会按大小/修改时间只传输与本地存在差异的文件。远程源带尾斜杠（仅传目录内容），
     * 本地目标为映射根目录，避免把远程根目录本身嵌套到本地根目录下。
     */
    private fun pullWholeDirectory(
        localBaseDir: String,
        remoteBaseDir: String,
        serverConfig: ServerConfig,
        pullOptions: SyncOptions,
        logCallback: ((String) -> Unit)?,
        progressCallback: ((SyncProgress) -> Unit)?
    ): SyncResult {
        logCallback?.invoke(
            DeployXBundle.message(
                "ssh.rsync.pullWholeDir",
                "${serverConfig.user}@${serverConfig.host}:${remoteBaseDir.trimEnd('/')}/",
                localBaseDir
            )
        )
        // remoteBaseDir 末尾补斜杠：rsync 仅传目录内容（而非目录本身），与本地映射根目录对齐
        return pull(localBaseDir, remoteBaseDir + "/", serverConfig, pullOptions, logCallback, progressCallback)
    }

    /**
     * [pullFilesFrom] 的内部实现：使用 --files-from 精确拉取非空相对路径列表。
     */
    private fun runPullFilesFrom(
        localBaseDir: String,
        remoteBaseDir: String,
        filePaths: List<String>,
        serverConfig: ServerConfig,
        pullOptions: SyncOptions,
        logCallback: ((String) -> Unit)?,
        progressCallback: ((SyncProgress) -> Unit)?
    ): SyncResult {
        val startTime = System.currentTimeMillis()
        val auth = prepareAuthContext(serverConfig)
        // 临时文件存放要同步的相对路径列表（NUL 分隔，配合 --from0）。
        // 改用 --files-from=FILE 而非 --files-from=- 通过 stdin 传入，
        // 避免 Windows 下与 cygwin rsync 的 stdin 交互出现编码/阻塞问题；
        // 使用完毕后自动删除。
        val filesFromTemp = File.createTempFile("deployx_files_from_pull_", ".txt").apply { deleteOnExit() }
        try {
            filesFromTemp.outputStream().use { output ->
                filePaths.forEach { path ->
                    output.write(path.toByteArray(StandardCharsets.UTF_8))
                    output.write(0)
                }
                output.flush()
            }

            val localBase = localBaseDir.trimEnd('/') + "/"
            val remoteBase = remoteBaseDir.trimEnd('/') + "/"

            // 确保本地目录存在
            val baseDir = File(localBase)
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val cmd = buildRsyncFilesFromCommand(
                localBase, remoteBase, serverConfig, pullOptions, auth.commandPrefix, filesFromTemp
            )
            val cmdStr = maskPassword(cmd).joinToString(" ")
            LOG.info("Executing rsync pull files-from: $cmdStr")
            logCallback?.invoke("[CMD] PULL files-from: $cmdStr")
            logCallback?.invoke("[FILES-FROM]")
            filePaths.forEach { logCallback?.invoke("  $it") }

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            auth.environment.forEach { (k, v) -> pb.environment()[k] = v }
            val process = pb.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val outputBuilder = StringBuilder()
            var totalSize = 0L
            val transferredFileList = mutableListOf<String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                outputBuilder.appendLine(currentLine)
                logCallback?.invoke(currentLine)

                val progress = parseProgressLine(currentLine)
                if (progress != null) {
                    progressCallback?.invoke(progress)
                }

                // 收集传输的文件列表
                if (isFileTransferLine(currentLine)) {
                    transferredFileList.add(currentLine.trim())
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
                logCallback?.invoke(DeployXBundle.message("ssh.rsync.pullFilesFromCompleted", duration))
                SyncResult(
                    success = true,
                    transferredFiles = transferredFileList.size,
                    transferredFileList = transferredFileList,
                    totalSize = totalSize,
                    duration = duration,
                    output = outputBuilder.toString()
                )
            } else {
                val errMsg = DeployXBundle.message("ssh.rsync.pullFilesFromFailed", exitCode)
                logCallback?.invoke("[ERROR] $errMsg")
                SyncResult(false, transferredFiles = transferredFileList.size, transferredFileList = transferredFileList, duration = duration, error = errMsg, output = outputBuilder.toString())
            }
        } catch (e: Exception) {
            LOG.error("rsync pull files-from execution failed", e)
            val errMsg = DeployXBundle.message("ssh.rsync.pullFilesFromException", e.message ?: "")
            logCallback?.invoke("[ERROR] $errMsg")
            return SyncResult(false, duration = System.currentTimeMillis() - startTime, error = errMsg)
        } finally {
            runCatching { filesFromTemp.delete() }
            auth.cleanup()
        }
    }

    /**
     * 合并多个 [SyncResult]（整目录拉取 + 精确文件拉取），用于混合场景。
     */
    private fun mergeSyncResults(results: List<SyncResult>, logCallback: ((String) -> Unit)?): SyncResult {
        if (results.isEmpty()) {
            return SyncResult(false, error = DeployXBundle.message("ssh.rsync.filesFromEmpty"))
        }
        val success = results.all { it.success }
        val transferredFiles = results.sumOf { it.transferredFiles }
        val transferredFileList = results.flatMap { it.transferredFileList }
        val totalSize = results.sumOf { it.totalSize }
        val duration = results.maxOfOrNull { it.duration } ?: 0L
        val errors = results.mapNotNull { it.error }
        val output = results.joinToString("\n") { it.output }
        return SyncResult(
            success = success,
            transferredFiles = transferredFiles,
            transferredFileList = transferredFileList,
            totalSize = totalSize,
            duration = duration,
            error = if (errors.isEmpty()) null else errors.joinToString("; "),
            output = output,
            attempts = results.maxOfOrNull { it.attempts } ?: 1
        )
    }

    /**
     * 判断当前是否运行在 Windows 环境下。
     * Windows 下的 cygwin rsync 需要对路径和命令做特殊处理：
     *  - 本地路径要转换为 /cygdrive/<drive>/... 形式，否则盘符 `C:` 会被 rsync 误识别为远程主机；
     *  - `-e` 后的 ssh 命令需要用引号整体包裹。
     */
    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /**
     * 将 Windows 本地路径转换为 Cygwin 风格路径。
     *
     * 例：`C:\Users\zml\...\src/` → `/cygdrive/c/Users/zml/.../src/`
     * 仅在 Windows 环境下转换；其他平台原样返回。
     * 已是 /cygdrive/ 开头的路径不重复转换。
     */
    private fun toCygwinPath(path: String): String {
        if (!isWindows()) return path
        if (path.startsWith("/cygdrive/", ignoreCase = true)) return path
        // 匹配形如 C:\ 或 C:/ 的盘符前缀，捕获盘符后的剩余部分（含尾部斜杠）
        val driveRegex = Regex("^([a-zA-Z]):[/\\\\](.*)$", RegexOption.IGNORE_CASE)
        val match = driveRegex.find(path) ?: return path
        val drive = match.groupValues[1].lowercase()
        val rest = match.groupValues[2].replace('\\', '/')
        return "/cygdrive/$drive/$rest"
    }

    /**
     * 构建 rsync 命令参数
     * @param commandPrefix 认证相关的前缀命令（sshpass 或为空，由 [prepareAuthContext] 决定）
     */
    private fun buildRsyncCommand(
        localPath: String,
        remotePath: String,
        serverConfig: ServerConfig,
        options: SyncOptions,
        commandPrefix: List<String>
    ): List<String> {
        val settings = FileSyncSettings.getInstance()
        val rsyncPath = settings.rsyncPath.ifEmpty { "rsync" }

        val cmd = mutableListOf<String>()
        cmd.addAll(commandPrefix)
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
        // Windows 下 cygwin rsync 会对 -e 后的值按空格重新拆分，
        // 用引号整体包裹 ssh 命令，避免被错误拆分。
        cmd.add(if (isWindows()) "\"$sshOpts\"" else sshOpts)

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

        // 源/目标路径：PULL 时远程在前、本地在后（从服务器拉取到本地）；否则本地在前、远程在后（上传）。
        // Windows 下本地路径需转换为 Cygwin 风格路径，否则盘符 `C:` 会被 rsync 误识别为远程主机。
        // 注意：rsync 中 /path/dir（无尾斜杠）表示同步目录本身，/path/dir/ 表示只同步目录内容。
        val localArg = toCygwinPath(localPath).trimEnd('/')
        val remoteArg = "${serverConfig.user}@${serverConfig.host}:$remotePath"
        val (source, dest) = if (options.direction == SyncDirection.PULL) {
            remoteArg to localArg
        } else {
            localArg to remoteArg
        }
        cmd.add(source)
        cmd.add(dest)

        return cmd
    }

    /**
     * 构建 --files-from 模式的 rsync 命令参数
     * @param commandPrefix 认证相关的前缀命令（sshpass 或为空，由 [prepareAuthContext] 决定）
     * @param filesFromFile 存放要同步的相对路径列表的临时文件（NUL 分隔，配合 --from0）
     */
    private fun buildRsyncFilesFromCommand(
        sourceBaseDir: String,
        remoteBaseDir: String,
        serverConfig: ServerConfig,
        options: SyncOptions,
        commandPrefix: List<String>,
        filesFromFile: File
    ): List<String> {
        val settings = FileSyncSettings.getInstance()
        val rsyncPath = settings.rsyncPath.ifEmpty { "rsync" }

        val cmd = mutableListOf<String>()
        cmd.addAll(commandPrefix)
        cmd.add(rsyncPath)

        val userOptions = settings.rsyncOptions.trim()
        if (userOptions.isNotEmpty()) {
            cmd.addAll(userOptions.split("\\s+".toRegex()).filter { it.isNotEmpty() })
        } else {
            cmd.add("-avz")
            if (settings.showProgress) cmd.add("--progress")
            cmd.add("--stats")
        }

        // files-from 模式必须显式启用递归：-a 在该模式下不会展开出 -r，
        // 缺少 -r 时选中目录只会创建目录结构，不会传输目录内的文件。
        // hasRecursiveOption 仅检测显式 -r/--recursive，故 -avz 也会补加 -r。
        if (!hasRecursiveOption(cmd)) {
            cmd.add("-r")
        }
        // 从临时文件读取要同步的文件列表（NUL 分隔，配合 --from0）
        // Windows 下该文件路径同样需转为 Cygwin 风格，否则盘符会被 rsync 误识别为远程主机
        cmd.add("--files-from=${toCygwinPath(filesFromFile.absolutePath)}")
        cmd.add("--from0")

        val sshOpts = buildSshOptions(serverConfig)
        cmd.add("-e")
        // Windows 下 cygwin rsync 会对 -e 后的值按空格重新拆分，
        // 用引号整体包裹 ssh 命令，避免被错误拆分。
        cmd.add(if (isWindows()) "\"$sshOpts\"" else sshOpts)

        for (pattern in options.excludePatterns) {
            cmd.add("--exclude=$pattern")
        }
        if (options.dryRun) cmd.add("--dry-run")
        if (options.deleteRemote) cmd.add("--delete")

        // 源/目标路径：PULL 时远程在前、本地在后（从服务器拉取到本地）；否则本地在前、远程在后（上传）。
        // --files-from 中的相对路径始终相对于“源”目录解析。
        // Windows 下本地路径需转换为 Cygwin 风格路径，否则盘符 `C:` 会被 rsync 误识别为远程主机。
        val localArg = toCygwinPath(sourceBaseDir).trimEnd('/') + "/"
        val remoteArg = "${serverConfig.user}@${serverConfig.host}:${remoteBaseDir.trimEnd('/')}/"
        val (source, dest) = if (options.direction == SyncDirection.PULL) {
            remoteArg to localArg
        } else {
            localArg to remoteArg
        }
        cmd.add(source)
        cmd.add(dest)
        return cmd
    }

    /**
     * 判断命令参数中是否包含显式的递归选项。
     *
     * 注意：仅检测 `-r` / `--recursive`，**不把 `-a` 视为递归**。
     * 原因：`-a`（archive）虽在普通模式下等价于 `-rlptgoD`（含 `-r`），但在 `--files-from`
     * 模式下 rsync 不会从 `-a` 展开递归——实测 `-avz --files-from` 只会创建目录结构而不会
     * 递归传输目录内的文件（rsync 3.2.7 验证）。因此 [buildRsyncFilesFromCommand] 必须在
     * 仅有 `-a` 而无显式 `-r` 时补加 `-r`，否则选中目录同步时子目录下的文件不会被传输。
     */
    private fun hasRecursiveOption(cmd: List<String>): Boolean {
        return cmd.any { arg ->
            arg == "-r" || arg == "--recursive" ||
                    (arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).contains('r'))
        }
    }

    /**
     * 准备认证上下文。
     *
     * - 若 sshpass 可用，优先用 `sshpass -p <password>` 包装命令。
     * - 若使用密码认证但 sshpass 不可用（例如 Windows 上只装了 rsync 没有 sshpass），
     *   则回退到 `SSH_ASKPASS` 机制：现代 OpenSSH（含 Windows 原生 OpenSSH）支持通过该环境变量
     *   在非交互场景下提供密码，从而无需 sshpass 也能用 rsync 完成密码认证传输。
     * - 密钥认证无需任何包装，返回空上下文。
     *
     * @return 包含命令前缀、额外环境变量以及执行后需清理的临时文件的上下文
     */
    private fun prepareAuthContext(serverConfig: ServerConfig): AuthContext {
        val needsPassword = serverConfig.authType == ServerConfig.AuthType.PASSWORD &&
                serverConfig.password.isNotEmpty()
        if (!needsPassword) {
            return AuthContext(emptyList(), emptyMap())
        }

        // 优先使用 sshpass（实时检测，避免依赖过期的缓存标志）
        if (RsyncWrapper.isSshpassAvailable()) {
            return AuthContext(
                commandPrefix = listOf("sshpass", "-p", serverConfig.password),
                environment = emptyMap()
            )
        }

        // 回退：使用 SSH_ASKPASS 机制提供密码
        return buildAskpassContext(serverConfig.password)
    }

    /**
     * 构建基于 SSH_ASKPASS 的认证上下文。
     *
     * 将密码写入临时文件，并生成一个极简的辅助脚本，该脚本只是把密码文件内容输出到标准输出。
     * 随后通过环境变量 SSH_ASKPASS / SSH_ASKPASS_REQUIRE 让底层 ssh 在需要密码时调用该脚本。
     * 传输结束后清理临时文件。
     *
     * 平台差异（均已实测验证）：
     * - Linux/macOS：用 `.sh` 脚本（`#!/bin/sh\ncat "路径"`），ssh 通过 execl 执行，支持 shebang。
     * - Windows：必须用 `.cmd` 脚本（`@type "Windows路径"`）。常见的 cygwin rsync 精简包只含
     *   rsync.exe + ssh.exe + cygwin DLL，**不含 sh.exe**，cygwin ssh 调用 askpass 时用的是
     *   `posix_spawnp`（而非 fork+execl），该函数不解析 shebang，遇到 `.sh` 脚本会因找不到
     *   `/bin/sh` 报 `posix_spawnp: No such file or directory`；而 `.cmd` 可经 cygwin 的扩展名
     *   关联由 `cmd.exe /c` 执行。SSH_ASKPASS 与脚本内密码文件路径均用 Windows 路径（实测 cygwin
     *   ssh 的 posix_spawnp 能正确解析并执行 `C:\...\askpass.cmd`，密码被成功读取）。
     */
    private fun buildAskpassContext(password: String): AuthContext {
        val pwFile = File.createTempFile("deployx_askpass_pw_", ".tmp").apply {
            deleteOnExit()
            // 不写入换行符，保证 @type / cat 输出的密码与原始密码逐字节一致
            writeBytes(password.toByteArray(StandardCharsets.UTF_8))
        }
        val isWin = isWindows()
        // Windows 用 .cmd（posix_spawnp 能执行，内容用 Windows 路径）；
        // 其他平台用 .sh（execl 支持 shebang）。
        val helperExt = if (isWin) ".cmd" else ".sh"
        val helper = File.createTempFile("deployx_askpass_", helperExt).apply {
            deleteOnExit()
            if (isWin) {
                // @type 输出文件原始字节，无额外字符；\r\n 为 cmd 脚本标准换行
                writeText("@type \"${pwFile.absolutePath}\"\r\n")
            } else {
                writeText("#!/bin/sh\ncat \"${pwFile.absolutePath}\"\n")
                setExecutable(true)
            }
        }
        // SSH_ASKPASS 的值需是执行环境可解析的路径：
        // Windows 用 Windows 路径（cygwin posix_spawnp 能解析）；其他平台用 POSIX 路径。
        val askpassEnv = helper.absolutePath
        val environment = mapOf(
            "SSH_ASKPASS" to askpassEnv,
            "SSH_ASKPASS_REQUIRE" to "force",
            "DISPLAY" to ":0"
        )
        return AuthContext(
            commandPrefix = emptyList(),
            environment = environment,
            cleanup = {
                runCatching { pwFile.delete() }
                runCatching { helper.delete() }
            }
        )
    }

    /**
     * rsync 认证上下文：命令前缀、额外环境变量、执行后清理回调。
     */
    private data class AuthContext(
        val commandPrefix: List<String>,
        val environment: Map<String, String>,
        val cleanup: () -> Unit = {}
    )

    /**
     * 构建 SSH 选项字符串
     * 密码认证时返回 ssh 命令（sshpass 已在外层处理）
     * 密钥认证时添加 -i 参数
     */
    private fun buildSshOptions(serverConfig: ServerConfig): String {
        val opts = StringBuilder("${resolveSshCommand()} -o StrictHostKeyChecking=no -o ConnectTimeout=")
        opts.append(FileSyncSettings.getInstance().connectTimeout / 1000) // 转为秒
        // 不写 known_hosts：cygwin ssh 默认写 /home/<user>/.ssh/known_hosts，精简 cygwin 包可能无此目录，
        // 报 "Could not create directory" 警告；显式指向 /dev/null 可消除（StrictHostKeyChecking=no 已禁用校验）
        opts.append(" -o UserKnownHostsFile=/dev/null")

        if (serverConfig.port != 22) {
            opts.append(" -p ${serverConfig.port}")
        }

        if (serverConfig.authType == ServerConfig.AuthType.KEY && serverConfig.keyFile.isNotEmpty()) {
            opts.append(" -i ${serverConfig.keyFile}")
        }

        return opts.toString()
    }

    /**
     * 解析 ssh 命令。
     *
     * Windows 下 cygwin rsync 执行 `-e ssh ...` 时，会从 PATH 中查找 ssh，而 Windows 系统 PATH
     * 里的 `C:\Windows\System32\OpenSSH\ssh.exe`（Windows 原生 OpenSSH）通常优先于 cygwin 的 ssh.exe。
     * Windows 原生 ssh 与 cygwin rsync 混用会导致 SSH_ASKPASS 机制不兼容（原生 ssh 无法执行 .cmd
     * askpass，且路径处理与 cygwin 不一致），表现为 `0 bytes received` / exit code 12。
     *
     * 修复：Windows 下优先用与 rsync.exe 同目录的 cygwin ssh.exe（`<rsyncDir>/ssh.exe`），
     * 保证 rsync 与 ssh 处于同一 cygwin 运行环境。其他平台用裸 `ssh`（由 PATH 解析）。
     */
    private fun resolveSshCommand(): String {
        if (!isWindows()) return "ssh"
        val rsyncPath = FileSyncSettings.getInstance().rsyncPath.ifEmpty { "rsync" }
        val rsyncFile = File(rsyncPath)
        // rsyncPath 为绝对路径时，取同目录下的 ssh.exe；否则无法定位，退回裸 ssh
        if (rsyncFile.isAbsolute) {
            val sshExe = File(rsyncFile.parentFile, "ssh.exe")
            if (sshExe.exists()) {
                // 用正斜杠，避免后续拼入 -e "..." 引号时反斜杠转义问题
                return sshExe.absolutePath.replace('\\', '/')
            }
        }
        return "ssh"
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
     * 判断行是否为文件传输行（rsync -v 输出中的文件名）
     * 
     * rsync -v --progress 输出格式：
     *   文件名
     *   空行
     *   进度行（包含 %、bytes/s、xfr# 等）
     * 
     * 文件名行特征：
     *   - 不包含进度/统计关键字
     *   - 不是数字计数行（如 "0 files..."）
     *   - 不是统计信息行（如 "File list generation time: ..."）
     *   - 通常包含 .（扩展名）或 /（路径）
     *   - 不包含冒号后跟数字（如 "0.001 seconds"）
     */
    private fun isFileTransferLine(line: String): Boolean {
        val trimmed = line.trim()
        
        // 空行不是文件名
        if (trimmed.isEmpty()) return false
        
        // 进度行特征（包含这些关键字就不是文件名）
        val progressKeywords = listOf(
            "%", "bytes/s", "kB/s", "KB/s", "MB/s", "GB/s",
            "xfr#", "to-chk", "irc#", "to-check"
        )
        if (progressKeywords.any { trimmed.contains(it, ignoreCase = true) }) {
            return false
        }

        // 统计行特征（前缀匹配）
        val statsPrefixes = listOf(
            "sent ", "received ", "total size ", "Number of ",
            "files ...", "building file list", "done",
            "file list generation time", "file list transfer time",
            "literal data:", "matched data:", "total transferred file size",
            "number of files", "number of created", "number of deleted",
            "number of regular", "is running..."
        )
        if (statsPrefixes.any { trimmed.startsWith(it, ignoreCase = true) }) {
            return false
        }

        // 排除文件计数行：如 "0 files...", "100 files..."
        if (trimmed.matches(Regex("^\\s*\\d+\\s+files?\\s*\\.*\\s*$", RegexOption.IGNORE_CASE))) {
            return false
        }

        // 排除纯数字/符号/空格行
        if (trimmed.matches(Regex("^[\\d\\s,.:\\-+*/=<>()\\[\\]{}]+$"))) {
            return false
        }

        // 排除包含时间信息的行（如 "0.001 seconds"）
        if (trimmed.matches(Regex(".*:\\s*\\d+[\\d.]*\\s*(seconds|sec|ms|min|minutes)\\s*$", RegexOption.IGNORE_CASE))) {
            return false
        }

        // 排除 rsync 命令行和选项输出
        if (trimmed.startsWith("rsync ") || trimmed.startsWith("--") ||
            trimmed.startsWith("- ") || trimmed.startsWith("[")) {
            return false
        }

        // 排除 ssh / rsync 的诊断行（Warning: / Error: / Note: / Failed to ... / Could not ... 等）
        // 这类行可能含字母和句点，会被后面的"含扩展名"判断误认为文件名，
        // 例如 "Warning: Permanently added '172.16.18.235' (ED25519) to the list of known hosts."
        val diagnosticPrefixes = listOf(
            "warning:", "error:", "note:", "info:",
            "failed to ", "could not ", "cannot ", "unable to ",
            "permission denied", "connection ", "rsync:", "ssh:"
        )
        if (diagnosticPrefixes.any { trimmed.startsWith(it, ignoreCase = true) }) {
            return false
        }

        // 排除 rsync 输出的目录创建行（以 / 结尾，如 "antrun/"、"classes/db/desktop/"）。
        // rsync 传输目录内容时会先输出目录路径行，再输出其中的文件行；
        // 目录行不是"实际传输的文件"，不应计入 transferredFileList。
        if (trimmed.endsWith('/')) {
            return false
        }

        // 有效的文件传输行特征：包含文件扩展名或路径分隔符
        // 文件名通常：包含 .（扩展名）或 /（路径）或只是简单的文件名
        val hasValidPathChars = trimmed.any { it.isLetterOrDigit() }
        val hasExtensionOrPath = trimmed.contains('.') || trimmed.contains('/')
        val isSimpleFileName = trimmed.matches(Regex("^[a-zA-Z0-9_-]+$")) && trimmed.length <= 200
        
        return hasValidPathChars && (hasExtensionOrPath || isSimpleFileName)
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
