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
     * 预览同步（干跑模式）。
     *
     * 通过 TransferService 统一调度：rsync 可用时走 rsync --dry-run，
     * 不可用时走 SFTP dry-run（遍历本地文件列表，不实际传输）。
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

        val dryRunOptions = options.copy(dryRun = true)
        return TransferService.getInstance().transfer(localPath, remotePath, server, dryRunOptions, logCallback)
    }

    /**
     * 1.0.8 新增：预览拉取（干跑模式）。
     *
     * 与 [previewSync] 方向相反：从服务器远端目录下载到本地路径。rsync 可用时
     * 走 `rsync --dry-run`，否则降级到 SFTP dry-run；LOCAL 服务器走本地拷贝。
     *
     * @param remotePath 远端源目录或文件
     * @param localPath 本地目标目录
     */
    fun previewPull(
        remotePath: String,
        localPath: String,
        serverId: String,
        options: SyncOptions = SyncOptions(),
        logCallback: ((String) -> Unit)? = null
    ): SyncResult {
        LOG.info("Preview pull: $serverId:$remotePath -> $localPath")

        val server = ServerManager.getInstance().getServer(serverId)
            ?: return SyncResult(false, error = DeployXBundle.message("sync.error.serverNotFound", serverId))

        val dryRunOptions = options.copy(dryRun = true)
        return TransferService.getInstance().download(localPath, remotePath, server, dryRunOptions, logCallback)
    }

    /**
     * 检查 rsync 是否可用
     */
    fun isRsyncAvailable(): Boolean = RsyncWrapper.isRsyncAvailable()
}
