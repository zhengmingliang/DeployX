package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.SyncOptions
import com.alianga.idea.deploy.model.SyncResult
import com.alianga.idea.deploy.ssh.RsyncWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger

/**
 * 文件同步服务 - 负责执行文件同步操作
 */
@Service
class SyncService {

    companion object {
        private val LOG = Logger.getInstance(SyncService::class.java)

        fun getInstance(): SyncService =
            ApplicationManager.getApplication().getService(SyncService::class.java)
    }

    private val rsyncWrapper = RsyncWrapper()

    /**
     * 同步文件
     * @param logCallback 实时日志回调
     * @param progressCallback 进度回调
     */
    fun sync(
        localPath: String,
        remotePath: String,
        serverId: String,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null,
        progressCallback: ((RsyncWrapper.SyncProgress) -> Unit)? = null
    ): SyncResult {
        LOG.info("Starting sync: $localPath -> $serverId:$remotePath")

        val server = ServerManager.getInstance().getServer(serverId)
            ?: return SyncResult(false, error = DeployXBundle.message("sync.error.serverNotFound", serverId))

        return TransferService.getInstance().transfer(localPath, remotePath, server, options, logCallback, progressCallback)
    }

    /**
     * 预览同步（干跑模式）
     */
    fun previewSync(
        localPath: String,
        remotePath: String,
        serverId: String,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null
    ): SyncResult {
        LOG.info("Preview sync: $localPath -> $serverId:$remotePath")

        val server = ServerManager.getInstance().getServer(serverId)
            ?: return SyncResult(false, error = DeployXBundle.message("sync.error.serverNotFound", serverId))

        if (!TransferService.getInstance().canPreviewWithRsync()) {
            return SyncResult(false, error = DeployXBundle.message("sync.error.previewRequiresRsync"))
        }
        val dryRunOptions = options.copy(dryRun = true)
        return rsyncWrapper.sync(localPath, remotePath, server, dryRunOptions, logCallback)
    }

    /**
     * 检查 rsync 是否可用
     */
    fun isRsyncAvailable(): Boolean = RsyncWrapper.isRsyncAvailable()
}
