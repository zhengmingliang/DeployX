package com.alianga.idea.deploy.service

/**
 * 部署/同步操作的取消令牌（1.0.6 新增）。
 *
 * 设计目标：贯穿 UI -> DeployService -> TransferService -> RsyncWrapper/SftpTransferClient，
 * 让用户点击"终止"按钮后，正在运行的传输能尽快停止。
 *
 * - 线程安全：使用 [@Volatile][Volatile] 保证跨线程可见性，无需加锁。
 * - 协作式取消：传输循环在合适的位置调用 [throwIfCancelled] 主动检查；
 *   rsync 子进程还会被 [Process.destroyForcibly] 杀死以立即停止 IO。
 * - 异常语义：[DeployCancelledException] 是 RuntimeException，上层统一捕获并转为
 *   "已终止"的 SyncResult/DeployResult，不污染正常的错误处理路径。
 *
 * 使用方式：
 * ```
 * val token = DeployCancelToken()
 * // ... 启动后台任务，把 token 传下去 ...
 * // 用户点击终止按钮：
 * token.cancel()
 * // 传输循环中：
 * token.throwIfCancelled()  // 已取消时抛 DeployCancelledException
 * ```
 */
class DeployCancelToken {

    @Volatile
    private var cancelled = false

    /** 标记为已取消。多次调用安全。 */
    fun cancel() {
        cancelled = true
    }

    /** 是否已取消。 */
    fun isCancelled(): Boolean = cancelled

    /** 重置为未取消状态，便于令牌复用（一般每次操作新建一个令牌，不推荐复用）。 */
    fun reset() {
        cancelled = false
    }

    /**
     * 若已取消则抛出 [DeployCancelledException]。
     * 供传输循环、批次分组循环等在合适位置主动检查。
     */
    fun throwIfCancelled() {
        if (cancelled) {
            throw DeployCancelledException()
        }
    }
}

/**
 * 用户终止部署/同步时抛出的异常（1.0.6 新增）。
 *
 * 这是一个 [RuntimeException]，无需在方法签名上声明；
 * 上层（DeployService / FileSyncToolWindowPanel）统一捕获并转为"已终止"结果。
 */
class DeployCancelledException : RuntimeException("Deploy cancelled by user")
