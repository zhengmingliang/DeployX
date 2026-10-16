package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.ssh.SshConnection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 备份/解压服务 - 从 DeployService 抽取的远程文件备份与解压逻辑。
 *
 * 仅依赖 [SshConnection]（通过参数传入），无服务单例依赖，便于独立测试与复用。
 */
object BackupService {

    data class BackupResult(val success: Boolean, val path: String? = null, val error: String? = null)
    data class UnzipResult(val success: Boolean, val error: String? = null)

    /**
     * 将本次选择的多个远程文件/目录打成一个 tar.gz 备份包。
     *
     * 备份命名规则：以备份文件/目录名（取首个相对路径的末段名，可截断）为前缀 + _bak + 日期，
     * 例如单文件 `updates_bak_20260712_025456.tar.gz`；多文件时为 `dir_2files_bak_<时间戳>.tar.gz`。
     */
    fun doBackupSelected(
        sshConnection: SshConnection,
        remoteBaseDir: String,
        relativePaths: List<String>,
        backupDir: String,
        logCallback: ((String) -> Unit)? = null,
        cancelToken: DeployCancelToken? = null
    ): BackupResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

        val existingPaths = relativePaths.map { it.trim('/') }.filter { it.isNotBlank() }.filter { relativePath ->
            val remotePath = RemotePathUtils.joinRemotePath(remoteBaseDir, relativePath)
            sshConnection.executeCommand("test -e ${shellQuote(remotePath)}", cancelToken = cancelToken).success
        }

        if (existingPaths.isEmpty()) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.skipBackupFirstDeploy"))
            return BackupResult(true, null)
        }

        // 备份文件名前缀：取首个已存在路径的末段名（文件名或目录名），
        // 截断过长名称避免文件名超限，并清理特殊字符。
        val firstPath = existingPaths.first()
        val rawName = firstPath.trim('/').substringAfterLast('/').ifBlank { firstPath.trim('/') }
        val cleanName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifBlank { "deploy" }
        val safeName = if (cleanName.length > 32) cleanName.substring(0, 32) else cleanName
        // 单文件直接用其名；多文件用“首个名_nfiles”以体现批量
        val prefix = if (existingPaths.size == 1) safeName else "${safeName}_${existingPaths.size}files"
        val backupFile = "${backupDir.trimEnd('/')}/${prefix}_bak_${timestamp}.tar.gz"

        sshConnection.executeCommand("mkdir -p ${shellQuote(backupDir)}", cancelToken = cancelToken)

        logCallback?.invoke(DeployXBundle.message("deploy.log.compressingBackup", existingPaths.size, backupFile))
        existingPaths.forEach { logCallback?.invoke("  $it") }
        val quotedPaths = existingPaths.joinToString(" ") { shellQuote(it) }
        val result = sshConnection.executeCommand(
            "tar -czf ${shellQuote(backupFile)} -C ${shellQuote(remoteBaseDir)} $quotedPaths",
            cancelToken = cancelToken
        )
        return if (result.success) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupCompleteFile", backupFile))
            pruneOldBackups(sshConnection, backupDir, prefix, logCallback)
            BackupResult(true, backupFile)
        } else {
            BackupResult(false, error = result.error)
        }
    }

    /**
     * 执行备份
     * - 压缩包文件 -> 加日期后缀直接复制到备份目录
     * - 目录或非压缩文件 -> 压缩为 tar.gz（名称+日期），移动到备份目录
     *
     * @param backupSource 远程源路径（具体的文件或目录）
     * @param backupDir 备份目标目录
     */
    fun doBackup(
        sshConnection: SshConnection,
        backupSource: String,
        backupDir: String,
        logCallback: ((String) -> Unit)? = null,
        cancelToken: DeployCancelToken? = null
    ): BackupResult {
        // 检查源文件/目录是否存在
        val checkResult = sshConnection.executeCommand("test -e ${shellQuote(backupSource)}", cancelToken = cancelToken)
        if (!checkResult.success) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.skipBackupSourceNotFound", backupSource))
            return BackupResult(true, null)
        }

        // 确保备份目录存在
        sshConnection.executeCommand("mkdir -p ${shellQuote(backupDir)}", cancelToken = cancelToken)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sourceName = File(backupSource).name
        val isCompressed = sourceName.endsWith(".zip", true) ||
                sourceName.endsWith(".tar.gz", true) ||
                sourceName.endsWith(".tgz", true) ||
                sourceName.endsWith(".gz", true) ||
                sourceName.endsWith(".rar", true) ||
                sourceName.endsWith(".7z", true)

        val backupResult = if (isCompressed) {
            // 压缩包：加 _bak + 日期后缀，直接复制，保留原始扩展名（含 .tar.gz 双扩展名）
            val lowerName = sourceName.lowercase()
            val baseName: String
            val ext: String
            when {
                lowerName.endsWith(".tar.gz") -> { baseName = sourceName.removeSuffix(".tar.gz"); ext = "tar.gz" }
                lowerName.endsWith(".tar.bz2") -> { baseName = sourceName.removeSuffix(".tar.bz2"); ext = "tar.bz2" }
                lowerName.endsWith(".tar.xz") -> { baseName = sourceName.removeSuffix(".tar.xz"); ext = "tar.xz" }
                else -> { baseName = sourceName.substringBeforeLast("."); ext = sourceName.substringAfterLast(".") }
            }
            val backupFile = "$backupDir/${baseName}_bak_${timestamp}.$ext"
            logCallback?.invoke(DeployXBundle.message("deploy.log.archiveBackup", backupSource, backupFile))
            val copyResult = sshConnection.executeCommand("cp ${shellQuote(backupSource)} ${shellQuote(backupFile)}", cancelToken = cancelToken)
            Pair(copyResult, backupFile)
        } else {
            // 目录或非压缩文件：压缩为 tar.gz，命名统一为 {源名}_bak_{时间戳}.tar.gz
            val tarName = "${sourceName}_bak_${timestamp}.tar.gz"
            val tarPath = "$backupDir/$tarName"
            val sourceParent = File(backupSource).parent
            logCallback?.invoke(DeployXBundle.message("deploy.log.compressBackup", backupSource, tarPath))
            val tarResult = sshConnection.executeCommand(
                "tar -czf ${shellQuote(tarPath)} -C ${shellQuote(sourceParent ?: ".")} ${shellQuote(sourceName)}",
                cancelToken = cancelToken
            )
            Pair(tarResult, tarPath)
        }

        return if (backupResult.first.success) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupComplete"))
            // 备份文件名形如 {前缀}_bak_时间戳.扩展名，取最后一段 _bak_ 之前的名称作为清理前缀
            val prunePrefix = backupResult.second.substringAfterLast('/').substringBeforeLast("_bak_")
            if (prunePrefix.isNotBlank()) pruneOldBackups(sshConnection, backupDir, prunePrefix, logCallback)
            // 返回具体的备份文件路径（而非备份目录），便于报告与回滚时定位
            BackupResult(true, backupResult.second)
        } else {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupFailedWarn", backupResult.first.error))
            BackupResult(false, error = backupResult.first.error)
        }
    }

    /**
     * 按扩展名判断归档类型对应的解压命令（null 表示不支持）。
     * 服务端常见 .tar.gz/.tgz/.tar 一并支持，不只取决于远端是否安装 unzip。
     */
    fun archiveCommandFor(path: String): String? {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "tar -xzf"
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> "tar -xjf"
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> "tar -xJf"
            lower.endsWith(".tar") -> "tar -xf"
            lower.endsWith(".zip") -> "unzip -o"
            else -> null
        }
    }

    /**
     * 执行解压（1.1.1：按扩展名选择 unzip/tar，不再一侟 unzip，误解压 .jar 等会直接报清晰错误）
     */
    fun doUnzip(
        sshConnection: SshConnection,
        zipPath: String,
        destDir: String,
        cancelToken: DeployCancelToken? = null
    ): UnzipResult {
        val checkResult = sshConnection.executeCommand("test -f ${shellQuote(zipPath)}", cancelToken = cancelToken)
        if (!checkResult.success) {
            return UnzipResult(false, error = DeployXBundle.message("deploy.error.fileNotFound", zipPath))
        }

        val extractCmd = when (val cmd = archiveCommandFor(zipPath)) {
            "unzip -o" -> "unzip -o ${shellQuote(zipPath)} -d ${shellQuote(destDir)}"
            null -> return UnzipResult(false, error = DeployXBundle.message("deploy.error.unsupportedArchive", zipPath.substringAfterLast('/')))
            else -> "$cmd ${shellQuote(zipPath)} -C ${shellQuote(destDir)}"
        }

        sshConnection.executeCommand("mkdir -p ${shellQuote(destDir)}", cancelToken = cancelToken)

        val result = sshConnection.executeCommand(extractCmd, cancelToken = cancelToken)
        return if (result.success) {
            UnzipResult(true)
        } else {
            UnzipResult(false, error = result.error)
        }
    }

    /**
     * 备份成功后按设置 maxBackups 清理同一前缀的历史备份（1.1.1 复活死配置）。
     * 用 find + 单引号转义，避免 glob/shell 展开；超出的最旧备份删除，失败仅记日志不中断部署。
     */
    private fun pruneOldBackups(
        sshConnection: SshConnection,
        backupDir: String,
        prefix: String,
        logCallback: ((String) -> Unit)?
    ) {
        val keep = FileSyncSettings.getInstance().maxBackups
        if (keep <= 0 || prefix.isBlank()) return
        // 按修改时间从新到旧列出同前缀的历史备份（含本次）
        val listResult = sshConnection.executeCommand(
            "find ${shellQuote(backupDir)} -maxdepth 1 -type f -name ${shellQuote("${prefix}_bak_*")} -printf '%T@\\n%p\\n'"
        )
        if (!listResult.success) return
        val lines = listResult.output.lines().map { it.trim() }.filter { it.isNotBlank() }
        // 奇数行是时间戳，偶数行是路径，按时间倒序已是文件顺序
        val stale = lines.filterIndexed { idx, _ -> idx % 2 == 1 }.drop(keep).filter { it.startsWith("/") || it.contains('/') }
        var pruned = 0
        for (path in stale) {
            if (sshConnection.executeCommand("rm -f ${shellQuote(path)}").success) pruned++
        }
        if (pruned > 0) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.pruneBackups", pruned, keep))
        }
    }

    /** 单引号转义，用于拼接 shell 命令参数 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
