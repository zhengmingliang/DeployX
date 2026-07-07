package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.model.SyncOptions
import com.alianga.idea.deploy.model.SyncResult
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.alianga.idea.deploy.ssh.SftpTransferClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

/**
 * 传输服务：根据配置自动选择 rsync 或 SFTP fallback。
 */
@Service
class TransferService {

    enum class TransferMode { AUTO, RSYNC_ONLY, SFTP_ONLY }

    companion object {
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
        val rsyncAvailable = RsyncWrapper.isRsyncAvailable()
        val needsSshpass = serverConfig.authType == ServerConfig.AuthType.PASSWORD && serverConfig.password.isNotBlank()
        val sshpassAvailable = RsyncWrapper.isSshpassAvailable()

        return when (mode) {
            TransferMode.SFTP_ONLY -> {
                logCallback?.invoke("[TRANSFER] SFTP_ONLY 模式，使用 SFTP 上传")
                "sftp"
            }
            TransferMode.RSYNC_ONLY -> {
                if (!rsyncAvailable) {
                    logCallback?.invoke("[ERROR] RSYNC_ONLY 模式下未检测到 rsync，请安装 rsync 或切换到 AUTO/SFTP_ONLY")
                    "rsync"
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
}
