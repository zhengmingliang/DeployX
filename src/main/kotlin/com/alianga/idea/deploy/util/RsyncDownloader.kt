package com.alianga.idea.deploy.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Windows 下 rsync 一键下载安装工具。
 *
 * 从 GitHub releases（或备用镜像）下载 rsync-win.zip，解压到用户主目录下的
 * `~/.deploy-x/rsync-win/`，并返回其中 rsync.exe 的完整路径。
 *
 * 下载策略：优先 GitHub releases，失败后自动切换备用链接重试。
 */
object RsyncDownloader {

    private val LOG = Logger.getInstance(RsyncDownloader::class.java)

    /** GitHub releases 下载地址（latest） */
    const val GITHUB_DOWNLOAD_URL =
        "https://github.com/rn7s2/rsync-win/releases/latest/download/rsync-win.zip"

    /** GitHub releases 页面（用于手动下载引导） */
    const val GITHUB_RELEASES_PAGE = "https://github.com/rn7s2/rsync-win/releases/"

    /** 备用下载链接（国内镜像，GitHub 不可达时使用） */
    const val MIRROR_DOWNLOAD_URL =
        "https://openi.pcl.ac.cn/zml2015/file/raw/branch/master/att/rsync-win.zip"

    /** 旧版解压根目录：~/.deployx/rsync-win/（用于迁移） */
    private val oldInstallDir: File by lazy {
        File(System.getProperty("user.home"), ".deployx/rsync-win")
    }

    /** 解压根目录：~/.deploy-x/rsync-win/ */
    private val installDir: File by lazy {
        val dir = File(System.getProperty("user.home"), ".deploy-x/rsync-win")
        // 迁移旧的 .deployx 目录到 .deploy-x（如果存在且新目录不存在）
        migrateOldInstallDir(dir)
        dir
    }

    /**
     * 将旧版 `~/.deployx/rsync-win/` 迁移到 `~/.deploy-x/rsync-win/`。
     *
     * 历史版本（1.x）将 rsync 下载到 `.deployx/`（不含连字符），v2.0 后统一为 `.deploy-x/`。
     * 迁移仅在旧目录存在且新目录不存在时执行；若新目录已存在则跳过（避免覆盖已有文件）。
     * 迁移后尝试更新 FileSyncSettings 中的 rsyncPath 为新的路径。
     */
    private fun migrateOldInstallDir(newDir: File) {
        if (!oldInstallDir.exists()) return
        if (newDir.exists()) {
            LOG.info("Old install dir ${oldInstallDir.path} exists but new dir already present, skipping migration")
            return
        }
        try {
            LOG.info("Migrating rsync install from ${oldInstallDir.path} to ${newDir.path}")
            newDir.parentFile?.mkdirs()
            oldInstallDir.renameTo(newDir)
            LOG.info("Migration successful: ${oldInstallDir.path} → ${newDir.path}")
        } catch (e: Exception) {
            LOG.warn("Failed to migrate old install dir ${oldInstallDir.path}", e)
        }
    }

    /**
     * 下载并安装 rsync。
     *
     * @param onProgress 进度回调，参数为 0-100 的百分比（-1 表示无法确定总大小）
     * @return 安装成功时返回 rsync.exe 的 [File]，失败返回包含异常信息的 Result
     */
    fun downloadAndInstall(onProgress: (Int) -> Unit = {}): Result<File> {
        return try {
            val urls = listOf(GITHUB_DOWNLOAD_URL, MIRROR_DOWNLOAD_URL)
            var lastError: Throwable? = null

            for (url in urls) {
                try {
                    LOG.info("Downloading rsync-win.zip from: $url")
                    val zipFile = downloadZip(url, onProgress)
                    val rsyncExe = unzipAndFindRsync(zipFile)
                    zipFile.delete()
                    LOG.info("rsync installed at: ${rsyncExe.absolutePath}")
                    return Result.success(rsyncExe)
                } catch (e: Throwable) {
                    LOG.warn("Download from $url failed: ${e.message}")
                    lastError = e
                    // 继续尝试下一个 URL
                }
            }

            Result.failure(lastError ?: IllegalStateException("All download sources failed"))
        } catch (e: Throwable) {
            LOG.error("rsync download and install failed", e)
            Result.failure(e)
        }
    }

    ...

    /**
     * 下载 zip 文件到临时目录。
     */
    private fun downloadZip(url: String, onProgress: (Int) -> Unit): File {
        val tempFile = Files.createTempFile("deployx_rsync_", ".zip").toFile()
        tempFile.deleteOnExit()

        HttpRequests.request(url)
            .connectTimeout(15000)
            .readTimeout(60000)
            .saveToFile(tempFile, null)

        if (tempFile.length() < 1024) {
            throw IllegalStateException("Downloaded file too small (${tempFile.length()} bytes), possibly an error page")
        }

        onProgress(100)
        return tempFile
    }

    /**
     * 解压 zip 并查找 rsync.exe。
     *
     * rsync-win.zip 的结构通常是 cygwin64/ 目录下包含 rsync.exe、ssh.exe 等，
     * 也可能直接在根目录。这里递归查找第一个 rsync.exe。
     */
    private fun unzipAndFindRsync(zipFile: File): File {
        // 清理旧的安装目录
        if (installDir.exists()) {
            installDir.deleteRecursively()
        }
        installDir.mkdirs()

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryFile = File(installDir, entry.name)
                if (entry.isDirectory) {
                    entryFile.mkdirs()
                } else {
                    // 防止 zip slip 攻击：确保解压路径在 installDir 内
                    val canonicalDest = entryFile.canonicalPath
                    val canonicalInstall = installDir.canonicalPath
                    if (!canonicalDest.startsWith(canonicalInstall)) {
                        throw SecurityException("Zip entry outside target dir: ${entry.name}")
                    }
                    entryFile.parentFile?.mkdirs()
                    Files.copy(zis, entryFile.toPath())
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 递归查找 rsync.exe
        return findRsyncExe(installDir)
            ?: throw IllegalStateException("rsync.exe not found in extracted archive")
    }

    /**
     * 递归查找目录中的 rsync.exe。
     */
    private fun findRsyncExe(dir: File): File? {
        val files = dir.listFiles() ?: return null
        for (file in files) {
            if (file.isFile && file.name.equals("rsync.exe", ignoreCase = true)) {
                return file
            }
        }
        for (file in files) {
            if (file.isDirectory) {
                findRsyncExe(file)?.let { return it }
            }
        }
        return null
    }
}
