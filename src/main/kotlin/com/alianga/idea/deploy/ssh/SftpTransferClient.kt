package com.alianga.idea.deploy.ssh

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
        if (!localFile.exists()) return SyncResult(false, error = "本地路径不存在: $localPath")
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
        if (options.dryRun) {
            return SyncResult(false, error = "SFTP fallback 不支持 rsync dry-run 预览")
        }

        val connection = SshConnection(serverConfig)
        return try {
            logCallback?.invoke("[TRANSFER] 使用 SFTP fallback 上传")
            if (!connection.connect()) {
                return SyncResult(false, error = "SFTP 连接失败: ${serverConfig.displayAddress}")
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
                        logCallback?.invoke("[WARN] 本地路径不存在，跳过: $source")
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
            SyncResult(false, duration = System.currentTimeMillis() - startTime, error = "SFTP 上传失败: ${e.message}")
        } finally {
            connection.disconnect()
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
