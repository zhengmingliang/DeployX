package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.SyncOptions
import com.alianga.idea.deploy.model.SyncResult
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.alianga.idea.deploy.ssh.SftpTransferClient
import com.alianga.idea.deploy.util.RsyncDownloader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo

/**
 * 传输服务：根据配置自动选择 rsync 或 SFTP fallback。
 */
@Service
class TransferService {

    enum class TransferMode { AUTO, RSYNC_ONLY, SFTP_ONLY }

    companion object {
        private val LOG = Logger.getInstance(TransferService::class.java)

        fun getInstance(): TransferService =
            ApplicationManager.getApplication().getService(TransferService::class.java)
    }

    private val rsyncWrapper = RsyncWrapper()
    private val sftpClient = SftpTransferClient()

    fun transfer(
        localPath: String,
        remotePath: String,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): SyncResult {
        return when (chooseMethod(serverConfig, logCallback)) {
            "rsync" -> rsyncWrapper.sync(localPath, remotePath, serverConfig, options, logCallback, progressCallback)
            "sftp" -> sftpClient.upload(localPath, remotePath, serverConfig, options, logCallback, progressCallback)
            else -> SyncResult(false, error = "未知传输方式")
        }
    }

    fun transferFilesFrom(
        sourceBaseDir: String,
        remoteBaseDir: String,
        relativePaths: List<String>,
        serverConfig: ServerConfig,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): SyncResult {
        return when (chooseMethod(serverConfig, logCallback)) {
            "rsync" -> rsyncWrapper.syncFilesFrom(sourceBaseDir, remoteBaseDir, relativePaths, serverConfig, options, logCallback, progressCallback)
            "sftp" -> sftpClient.uploadFilesFrom(sourceBaseDir, remoteBaseDir, relativePaths, serverConfig, options, logCallback, progressCallback)
            else -> SyncResult(false, error = "未知传输方式")
        }
    }

    fun canPreviewWithRsync(): Boolean = RsyncWrapper.isRsyncAvailable()

    private fun chooseMethod(serverConfig: ServerConfig, logCallback: ((String) -> Unit)?): String {
        val settings = FileSyncSettings.getInstance()
        val mode = runCatching { TransferMode.valueOf(settings.transferMode) }.getOrDefault(TransferMode.AUTO)
        var rsyncAvailable = RsyncWrapper.isRsyncAvailable()
        val needsSshpass = serverConfig.authType == ServerConfig.AuthType.PASSWORD && serverConfig.password.isNotBlank()
        val sshpassAvailable = RsyncWrapper.isSshpassAvailable()

        // Windows 下首次检测到 rsync 不可用时，提示用户是否一键安装
        var userDeclinedInstall = false
        if (!rsyncAvailable && SystemInfo.isWindows && !settings.declinedRsyncAutoInstall) {
            val installed = promptAndInstallRsync(logCallback)
            if (installed) {
                rsyncAvailable = RsyncWrapper.isRsyncAvailable()
            } else {
                userDeclinedInstall = true
            }
        }

        return when (mode) {
            TransferMode.SFTP_ONLY -> {
                logCallback?.invoke("[TRANSFER] SFTP_ONLY 模式，使用 SFTP 上传")
                "sftp"
            }
            TransferMode.RSYNC_ONLY -> {
                if (!rsyncAvailable) {
                    if (userDeclinedInstall) {
                        // 用户已明确拒绝安装 rsync，降级 SFTP 而非硬失败
                        logCallback?.invoke("[TRANSFER] 用户跳过 rsync 安装，RSYNC_ONLY 模式降级为 SFTP 上传")
                        "sftp"
                    } else {
                        logCallback?.invoke("[ERROR] RSYNC_ONLY 模式下未检测到 rsync，请安装 rsync 或切换到 AUTO/SFTP_ONLY")
                        "rsync"
                    }
                } else {
                    if (needsSshpass && !sshpassAvailable) {
                        logCallback?.invoke(DeployXBundle.message("ssh.rsync.askpassFallback"))
                    } else {
                        logCallback?.invoke("[TRANSFER] RSYNC_ONLY 模式，使用 rsync 上传")
                    }
                    "rsync"
                }
            }
            TransferMode.AUTO -> {
                if (!rsyncAvailable) {
                    logCallback?.invoke("[TRANSFER] 未检测到 rsync，AUTO 模式降级为 SFTP 上传")
                    "sftp"
                } else {
                    if (needsSshpass && !sshpassAvailable) {
                        logCallback?.invoke(DeployXBundle.message("ssh.rsync.askpassFallback"))
                    } else {
                        logCallback?.invoke("[TRANSFER] 使用 rsync 上传")
                    }
                    "rsync"
                }
            }
        }
    }

    /**
     * Windows 下首次未检测到 rsync 时，弹窗询问用户是否自动安装。
     *
     * - 用户选"是"：后台下载安装 rsync，成功后更新 rsyncPath 并返回 true
     * - 用户选"否"：标记 declinedRsyncAutoInstall=true，返回 false（后续不再提示，自动降级 SFTP）
     *
     * 本方法在后台线程调用，通过 invokeAndWait 弹窗阻塞等待用户选择。
     */
    private fun promptAndInstallRsync(logCallback: ((String) -> Unit)?): Boolean {
        val settings = FileSyncSettings.getInstance()

        // 在 EDT 上弹窗，阻塞等待用户选择
        val userChoice = IntArray(1)
        ApplicationManager.getApplication().invokeAndWait {
            userChoice[0] = Messages.showYesNoDialog(
                DeployXBundle.message("settings.rsync.prompt.message"),
                DeployXBundle.message("settings.rsync.prompt.title"),
                Messages.getQuestionIcon()
            )
        }

        if (userChoice[0] != Messages.YES) {
            // 用户选择"否"，标记不再提示
            settings.declinedRsyncAutoInstall = true
            logCallback?.invoke("[TRANSFER] 用户跳过 rsync 安装，后续将使用 SFTP 上传")
            return false
        }

        // 用户选择"是"，执行下载安装
        logCallback?.invoke(DeployXBundle.message("settings.rsync.download.installing"))
        return try {
            val result = RsyncDownloader.downloadAndInstall()
            result.fold(
                onSuccess = { rsyncExe ->
                    val path = rsyncExe.absolutePath.replace('\\', '/')
                    settings.rsyncPath = path
                    logCallback?.invoke(DeployXBundle.message("settings.rsync.download.success", path))
                    true
                },
                onFailure = { error ->
                    LOG.warn("rsync auto-install failed: ${error.message}")
                    logCallback?.invoke(DeployXBundle.message("settings.rsync.download.failed", error.message ?: ""))
                    // 安装失败也标记为已提示，避免每次传输都弹窗（用户可在设置面板手动重试）
                    settings.declinedRsyncAutoInstall = true
                    false
                }
            )
        } catch (e: Exception) {
            LOG.warn("rsync auto-install exception", e)
            logCallback?.invoke(DeployXBundle.message("settings.rsync.download.failed", e.message ?: ""))
            settings.declinedRsyncAutoInstall = true
            false
        }
    }
}
