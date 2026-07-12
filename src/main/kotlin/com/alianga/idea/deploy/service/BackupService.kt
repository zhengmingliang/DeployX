package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
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
        logCallback: ((String) -> Unit)? = null
    ): BackupResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

        val existingPaths = relativePaths.map { it.trim('/') }.filter { it.isNotBlank() }.filter { relativePath ->
            val remotePath = RemotePathUtils.joinRemotePath(remoteBaseDir, relativePath)
            sshConnection.executeCommand("test -e ${shellQuote(remotePath)}").success
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

        sshConnection.executeCommand("mkdir -p ${shellQuote(backupDir)}")

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
            val copyResult = sshConnection.executeCommand("cp ${shellQuote(backupSource)} ${shellQuote(backupFile)}")
            Pair(copyResult, backupFile)
        } else {
            // 目录或非压缩文件：压缩为 tar.gz，命名统一为 {源名}_bak_{时间戳}.tar.gz
            val tarName = "${sourceName}_bak_${timestamp}.tar.gz"
            val tarPath = "$backupDir/$tarName"
            val sourceParent = File(backupSource).parent
            logCallback?.invoke(DeployXBundle.message("deploy.log.compressBackup", backupSource, tarPath))
            val tarResult = sshConnection.executeCommand(
                "tar -czf ${shellQuote(tarPath)} -C ${shellQuote(sourceParent ?: ".")} ${shellQuote(sourceName)}"
            )
            Pair(tarResult, tarPath)
        }

        return if (backupResult.first.success) {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupComplete"))
            // 返回具体的备份文件路径（而非备份目录），便于报告与回滚时定位
            BackupResult(true, backupResult.second)
        } else {
            logCallback?.invoke(DeployXBundle.message("deploy.log.backupFailedWarn", backupResult.first.error))
            BackupResult(false, error = backupResult.first.error)
        }
    }

    /**
     * 执行解压
     */
    fun doUnzip(
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

    /** 单引号转义，用于拼接 shell 命令参数 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
