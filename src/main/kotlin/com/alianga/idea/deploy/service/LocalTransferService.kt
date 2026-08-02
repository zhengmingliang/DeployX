package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.SyncOptions
import com.alianga.idea.deploy.model.SyncResult
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * 本地文件传输服务（1.0.6 新增）。
 *
 * 用于 [ServerConfig.ServerType.LOCAL] 类型的服务器：直接在本机文件系统间做增量拷贝，
 * 不依赖 SSH/rsync。典型场景是"投产增量文件更新"--把当前项目里改动的文件，
 * 按映射的目录结构增量更新到本地的投产目录。
 *
 * 增量策略：与 rsync 一致，按文件大小 + 修改时间判断是否需要拷贝；
 * 已存在且大小/修改时间相同的文件跳过，保留目标文件修改时间以便下次增量判断。
 *
 * 设计上与 [com.alianga.idea.deploy.ssh.RsyncWrapper] / [com.alianga.idea.deploy.ssh.SftpTransferClient]
 * 对称：返回 [SyncResult]、复用 [RsyncWrapper.SyncProgress] 进度模型，
 * 供 [TransferService] 统一分流，[ReportBuilder] 复用报告生成。
 */
@Service
class LocalTransferService {

    companion object {
        private val LOG = Logger.getInstance(LocalTransferService::class.java)

        fun getInstance(): LocalTransferService =
            ApplicationManager.getApplication().getService(LocalTransferService::class.java)
    }

    /**
     * 本地增量拷贝（PUSH 语义：sourceBaseDir -> targetBaseDir）。
     *
     * @param sourceBaseDir 本地源基础目录（通常是映射的 localDir）
     * @param targetBaseDir 本地目标基础目录（复用映射的 remoteDir 字段，对 LOCAL 服务器表示本地目标路径）
     * @param relativePaths 相对 [sourceBaseDir] 的路径列表（与 rsync --files-from 语义一致）；
     *        空字符串表示整目录递归拷贝
     * @param options 同步选项（excludePatterns / dryRun 生效；direction 对本地拷贝无意义，源/目标固定）
     * @param cancelToken 取消令牌，用户终止时抛 [DeployCancelledException]
     */
    fun copyFilesFrom(
        sourceBaseDir: String,
        targetBaseDir: String,
        relativePaths: List<String>,
        options: SyncOptions = SyncOptions(),
        cancelToken: DeployCancelToken? = null,
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): SyncResult {
        val startTime = System.currentTimeMillis()
        if (relativePaths.isEmpty()) {
            return SyncResult(false, error = DeployXBundle.message("ssh.rsync.filesFromEmpty"))
        }

        val sourceBase = Paths.get(sourceBaseDir)
        val targetBase = Paths.get(targetBaseDir)
        val matchers = createMatchers(options.excludePatterns)

        // 校验源目录
        val sourceBaseFile = sourceBase.toFile()
        if (!sourceBaseFile.exists()) {
            val msg = DeployXBundle.message("ssh.rsync.baseDirNotFound", sourceBaseDir)
            logCallback?.invoke("[ERROR] $msg")
            return SyncResult(false, error = msg)
        }

        logCallback?.invoke(DeployXBundle.message("transfer.local.using"))
        logCallback?.invoke("[LOCAL] ${sourceBaseDir} -> ${targetBaseDir}")
        logCallback?.invoke("[FILES-FROM]")
        relativePaths.forEach { logCallback?.invoke("  $it") }

        // 预扫描：收集待拷贝文件列表（同时用于 dry-run 与进度估算）
        val pendingFiles = mutableListOf<FileScanItem>()
        try {
            relativePaths.forEach { rawRelative ->
                cancelToken?.throwIfCancelled()
                val relative = rawRelative.trim('/').replace("\\", "/")
                val source: Path = if (relative.isBlank()) sourceBase else sourceBase.resolve(relative).normalize()
                if (!Files.exists(source)) {
                    logCallback?.invoke(DeployXBundle.message("ssh.sftp.skipLocalNotFound", source))
                    return@forEach
                }
                scanFiles(sourceBase, source, matchers, pendingFiles, cancelToken)
            }
        } catch (e: DeployCancelledException) {
            return cancelledResult(startTime)
        }

        // dry-run：仅输出待拷贝列表，不实际拷贝
        if (options.dryRun) {
            pendingFiles.forEach { item ->
                logCallback?.invoke("[DRY-RUN] ${item.source} -> ${targetBase.resolve(item.relative)}")
            }
            return SyncResult(
                success = true,
                transferredFiles = pendingFiles.size,
                transferredFileList = pendingFiles.map { it.relative },
                totalSize = pendingFiles.sumOf { it.size },
                duration = System.currentTimeMillis() - startTime,
                output = "Local dry-run: ${pendingFiles.size} file(s) would be copied"
            )
        }

        // 实际拷贝
        val transferredFiles = mutableListOf<String>()
        var totalSize = 0L
        val totalCount = pendingFiles.size.coerceAtLeast(1)
        try {
            pendingFiles.forEachIndexed { index, item ->
                cancelToken?.throwIfCancelled()
                val target = targetBase.resolve(item.relative)
                // 确保目标父目录存在
                Files.createDirectories(target.parent)
                // 增量判断：大小 + 修改时间一致则跳过
                if (shouldSkip(item.source, target)) {
                    logCallback?.invoke("[SKIP] ${item.relative} (up to date)")
                } else {
                    logCallback?.invoke("[COPY] ${item.source} -> $target")
                    Files.copy(item.source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    // 显式保留修改时间，确保下次增量判断准确（COPY_ATTRIBUTES 通常已包含，这里兜底）
                    runCatching { Files.setAttribute(target, "lastModifiedTime", item.lastModifiedTime) }
                    transferredFiles.add(item.relative)
                    totalSize += item.size
                }
                val percentage = ((index + 1) * 100 / totalCount).coerceIn(0, 100)
                progressCallback?.invoke(
                    RsyncWrapper.SyncProgress(
                        currentFile = item.relative,
                        percentage = percentage
                    )
                )
            }
        } catch (e: DeployCancelledException) {
            logCallback?.invoke(DeployXBundle.message("toolwindow.log.aborted"))
            return SyncResult(
                success = false,
                transferredFiles = transferredFiles.size,
                transferredFileList = transferredFiles,
                totalSize = totalSize,
                duration = System.currentTimeMillis() - startTime,
                error = DeployXBundle.message("transfer.cancelled"),
                output = "Local copy cancelled"
            )
        } catch (e: Exception) {
            LOG.error("Local file copy failed", e)
            val errMsg = DeployXBundle.message("ssh.sftp.uploadFailed", e.message ?: "")
            return SyncResult(
                success = false,
                transferredFiles = transferredFiles.size,
                transferredFileList = transferredFiles,
                totalSize = totalSize,
                duration = System.currentTimeMillis() - startTime,
                error = errMsg
            )
        }

        val duration = System.currentTimeMillis() - startTime
        logCallback?.invoke(DeployXBundle.message("transfer.local.completed", duration))
        return SyncResult(
            success = true,
            transferredFiles = transferredFiles.size,
            transferredFileList = transferredFiles,
            totalSize = totalSize,
            duration = duration,
            output = "Local copy: ${transferredFiles.size} file(s) copied"
        )
    }

    /**
     * 递归扫描源路径下的文件，收集待拷贝项（应用排除规则）。
     * 空相对路径（整目录）会扫描 sourceBase 本身的内容。
     */
    private fun scanFiles(
        sourceBase: Path,
        source: Path,
        matchers: List<PathMatcher>,
        out: MutableList<FileScanItem>,
        cancelToken: DeployCancelToken?
    ) {
        if (Files.isDirectory(source)) {
            val opts = java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
            Files.walkFileTree(source, opts, Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    cancelToken?.throwIfCancelled()
                    val rel = sourceBase.relativize(file).toString().replace(File.separatorChar, '/')
                    if (rel.isBlank() || isExcluded(rel, matchers)) return FileVisitResult.CONTINUE
                    out.add(FileScanItem(file, rel, attrs.size(), attrs.lastModifiedTime()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    LOG.warn("Failed to visit file: $file", exc)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            val rel = sourceBase.relativize(source).toString().replace(File.separatorChar, '/')
            if (!isExcluded(rel, matchers)) {
                val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
                out.add(FileScanItem(source, rel, attrs.size(), attrs.lastModifiedTime()))
            }
        }
    }

    /**
     * 增量判断：目标文件存在且大小 + 修改时间均一致时跳过。
     * 与 rsync 的默认行为（按 size + mtime）一致。
     */
    private fun shouldSkip(source: Path, target: Path): Boolean {
        if (!Files.exists(target)) return false
        val srcAttrs = runCatching { Files.readAttributes(source, BasicFileAttributes::class.java) }.getOrNull() ?: return false
        val dstAttrs = runCatching { Files.readAttributes(target, BasicFileAttributes::class.java) }.getOrNull() ?: return false
        if (srcAttrs.size() != dstAttrs.size()) return false
        // 修改时间比较：rsync 默认按秒级精度比较，这里用毫秒精度更严格也无妨（同源拷贝 mtime 一致）
        val srcTime = srcAttrs.lastModifiedTime().toMillis()
        val dstTime = dstAttrs.lastModifiedTime().toMillis()
        return srcTime == dstTime
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

    private fun cancelledResult(startTime: Long): SyncResult {
        return SyncResult(
            success = false,
            duration = System.currentTimeMillis() - startTime,
            error = DeployXBundle.message("transfer.cancelled"),
            output = "Local copy cancelled"
        )
    }

    private data class FileScanItem(
        val source: Path,
        val relative: String,
        val size: Long,
        val lastModifiedTime: FileTime
    )
}
