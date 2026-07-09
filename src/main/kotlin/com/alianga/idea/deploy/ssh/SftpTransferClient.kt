package com.alianga.idea.deploy.ssh

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.SyncOptions
import com.alianga.idea.deploy.model.SyncResult
import com.jcraft.jsch.ChannelSftp
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths

/**
 * SFTP fallback 上传客户端。
 */
class SftpTransferClient {

    fun upload(
        localPath: String,
        remotePath: String,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): SyncResult {
        val localFile = File(localPath)
        if (!localFile.exists()) return SyncResult(false, error = DeployXBundle.message("ssh.sftp.localPathNotFound", localPath))
        val remoteBase = remotePath.trimEnd('/')
        val relative = localFile.name + if (localFile.isDirectory) "/" else ""
        return uploadFilesFrom(localFile.parent ?: ".", remoteBase, listOf(relative), serverConfig, options, logCallback, progressCallback)
    }

    fun uploadFilesFrom(
        sourceBaseDir: String,
        remoteBaseDir: String,
        relativePaths: List<String>,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): SyncResult {
        val startTime = System.currentTimeMillis()

        // dry-run 模式：遍历本地文件列表，收集待上传文件，不实际传输
        if (options.dryRun) {
            return dryRunFilesFrom(sourceBaseDir, remoteBaseDir, relativePaths, options, logCallback, startTime)
        }

        val connection = SshConnection(serverConfig)
        return try {
            logCallback?.invoke(DeployXBundle.message("ssh.sftp.usingFallback"))
            if (!connection.connect()) {
                return SyncResult(false, error = DeployXBundle.message("ssh.sftp.connectFailed", serverConfig.displayAddress))
            }
            val channel = connection.openSftpChannel()
            try {
                val sourceBase = Paths.get(sourceBaseDir)
                val remoteBase = remoteBaseDir.trimEnd('/')
                val matchers = createMatchers(options.excludePatterns)
                var count = 0
                var totalSize = 0L
                val transferredFiles = mutableListOf<String>()

                relativePaths.forEach { rawRelative ->
                    val relative = rawRelative.trim('/').replace("\\", "/")
                    if (relative.isBlank()) return@forEach
                    val source = sourceBase.resolve(relative).normalize()
                    if (!Files.exists(source)) {
                        logCallback?.invoke(DeployXBundle.message("ssh.sftp.skipLocalNotFound", source))
                        return@forEach
                    }
                    val uploaded = uploadPath(channel, sourceBase, source, remoteBase, matchers, logCallback, progressCallback, transferredFiles)
                    count += uploaded.first
                    totalSize += uploaded.second
                }

                SyncResult(
                    success = true,
                    transferredFiles = count,
                    transferredFileList = transferredFiles,
                    totalSize = totalSize,
                    duration = System.currentTimeMillis() - startTime,
                    output = "SFTP uploaded $count file(s)"
                )
            } finally {
                channel.disconnect()
            }
        } catch (e: Exception) {
            SyncResult(false, duration = System.currentTimeMillis() - startTime, error = DeployXBundle.message("ssh.sftp.uploadFailed", e.message ?: ""))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * dry-run 模式：遍历本地文件，收集待上传的文件列表和总大小，不实际传输。
     * 不需要 SSH 连接，仅扫描本地文件系统。
     */
    private fun dryRunFilesFrom(
        sourceBaseDir: String,
        remoteBaseDir: String,
        relativePaths: List<String>,
        options: SyncOptions,
        logCallback: ((String) -> Unit)?,
        startTime: Long
    ): SyncResult {
        val sourceBase = Paths.get(sourceBaseDir)
        val remoteBase = remoteBaseDir.trimEnd('/')
        val matchers = createMatchers(options.excludePatterns)
        val transferredFiles = mutableListOf<String>()
        var totalSize = 0L

        logCallback?.invoke(DeployXBundle.message("ssh.sftp.dryRunPreview"))

        relativePaths.forEach { rawRelative ->
            val relative = rawRelative.trim('/').replace("\\", "/")
            if (relative.isBlank()) return@forEach
            val source = sourceBase.resolve(relative).normalize()
            if (!Files.exists(source)) {
                logCallback?.invoke(DeployXBundle.message("ssh.sftp.skipLocalNotFound", source))
                return@forEach
            }
            collectFiles(sourceBase, source, remoteBase, matchers, logCallback, transferredFiles) { size ->
                totalSize += size
            }
        }

        return SyncResult(
            success = true,
            transferredFiles = transferredFiles.size,
            transferredFileList = transferredFiles,
            totalSize = totalSize,
            duration = System.currentTimeMillis() - startTime,
            output = "SFTP dry-run: ${transferredFiles.size} file(s) would be uploaded"
        )
    }

    /**
     * 递归收集待上传的文件（dry-run 用），不实际传输。
     */
    private fun collectFiles(
        sourceBase: Path,
        source: Path,
        remoteBase: String,
        matchers: List<PathMatcher>,
        logCallback: ((String) -> Unit)?,
        transferredFiles: MutableList<String>,
        onSize: (Long) -> Unit
    ) {
        if (Files.isDirectory(source)) {
            Files.walk(source).use { stream ->
                stream.filter { !Files.isDirectory(it) }.forEach { path ->
                    val rel = sourceBase.relativize(path).toString().replace(File.separatorChar, '/')
                    if (rel.isBlank() || isExcluded(rel, matchers)) return@forEach
                    val remotePath = joinRemotePath(remoteBase, rel)
                    logCallback?.invoke("[DRY-RUN] $path -> $remotePath")
                    transferredFiles.add(rel)
                    onSize(Files.size(path))
                }
            }
        } else {
            val rel = sourceBase.relativize(source).toString().replace(File.separatorChar, '/')
            if (!isExcluded(rel, matchers)) {
                val remotePath = joinRemotePath(remoteBase, rel)
                logCallback?.invoke("[DRY-RUN] $source -> $remotePath")
                transferredFiles.add(rel)
                onSize(Files.size(source))
            }
        }
    }

    private fun uploadPath(
        channel: ChannelSftp,
        sourceBase: Path,
        source: Path,
        remoteBase: String,
        matchers: List<PathMatcher>,
        logCallback: ((String) -> Unit)?,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)?,
        transferredFiles: MutableList<String> = mutableListOf()
    ): Pair<Int, Long> {
        var count = 0
        var totalSize = 0L
        if (Files.isDirectory(source)) {
            Files.walk(source).use { stream ->
                stream.forEach { path ->
                    val rel = sourceBase.relativize(path).toString().replace(File.separatorChar, '/')
                    if (rel.isBlank() || isExcluded(rel, matchers)) return@forEach
                    val remotePath = joinRemotePath(remoteBase, rel)
                    if (Files.isDirectory(path)) {
                        ensureRemoteDir(channel, remotePath)
                    } else {
                        ensureRemoteDir(channel, remotePath.substringBeforeLast('/', remoteBase))
                        logCallback?.invoke("[SFTP] $path -> $remotePath")
                        channel.put(path.toString(), remotePath)
                        count++
                        totalSize += Files.size(path)
                        transferredFiles.add(rel)
                        progressCallback?.invoke(RsyncWrapper.SyncProgress(currentFile = rel, percentage = 100))
                    }
                }
            }
        } else {
            val rel = sourceBase.relativize(source).toString().replace(File.separatorChar, '/')
            if (!isExcluded(rel, matchers)) {
                val remotePath = joinRemotePath(remoteBase, rel)
                ensureRemoteDir(channel, remotePath.substringBeforeLast('/', remoteBase))
                logCallback?.invoke("[SFTP] $source -> $remotePath")
                channel.put(source.toString(), remotePath)
                count++
                totalSize += Files.size(source)
                transferredFiles.add(rel)
                progressCallback?.invoke(RsyncWrapper.SyncProgress(currentFile = rel, percentage = 100))
            }
        }
        return count to totalSize
    }

    private fun ensureRemoteDir(channel: ChannelSftp, remoteDir: String) {
        val normalized = remoteDir.replace("\\", "/").trimEnd('/')
        if (normalized.isBlank() || normalized == "/") return
        var current = if (normalized.startsWith('/')) "/" else ""
        normalized.trim('/').split('/').filter { it.isNotBlank() }.forEach { part ->
            current = if (current == "/" || current.isBlank()) "$current$part" else "$current/$part"
            try {
                channel.cd(current)
            } catch (_: Exception) {
                channel.mkdir(current)
            }
        }
    }

    private fun joinRemotePath(base: String, relative: String): String {
        val normalizedBase = base.trimEnd('/')
        val normalizedRelative = relative.trim('/')
        return if (normalizedRelative.isBlank()) normalizedBase else "$normalizedBase/$normalizedRelative"
    }

    private fun createMatchers(patterns: List<String>): List<PathMatcher> {
        return patterns.filter { it.isNotBlank() }.mapNotNull { pattern ->
            runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrNull()
        }
    }

    private fun isExcluded(relativePath: String, matchers: List<PathMatcher>): Boolean {
        if (matchers.isEmpty()) return false
        val path = Paths.get(relativePath)
        return matchers.any { matcher -> matcher.matches(path) || matcher.matches(path.fileName) }
    }
}
