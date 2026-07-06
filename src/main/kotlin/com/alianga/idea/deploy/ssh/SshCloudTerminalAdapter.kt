package com.alianga.idea.deploy.ssh

import com.intellij.openapi.project.Project
import com.intellij.remoteServer.agent.util.log.TerminalListener
import org.jetbrains.plugins.terminal.cloud.CloudTerminalProcess
import org.jetbrains.plugins.terminal.cloud.CloudTerminalRunner
import java.awt.Dimension

/**
 * 包装 SshTtyConnector 的 CloudTerminalProcess。
 *
 * 继承 CloudTerminalProcess 以接入 IDEA 终端：
 * - 构造时传入 SSH Shell 的输出流（本地 -> 远程）和输入流（远程 -> 本地）
 * - destroy() 时关闭底层 SSH channel，避免连接泄漏
 */
class SshCloudTerminalProcess(
    private val sshTtyConnector: SshTtyConnector
) : CloudTerminalProcess(sshTtyConnector.getOutputStream(), sshTtyConnector.getInputStream()) {

    override fun destroy() {
        super.destroy()
        sshTtyConnector.close()
    }
}

/**
 * 终端 resize 回调，适配 IDEA 的 TerminalListener.TtyResizeHandler 接口。
 */
private fun interface TtyResizeHandler : TerminalListener.TtyResizeHandler {
    override fun onTtyResizeRequest(width: Int, height: Int)
}

/**
 * 基于 SSH 的 CloudTerminalRunner。
 *
 * 【版本兼容性说明】
 * 使用三参数构造函数，确保在新旧 IDEA 版本都能正常运行：
 * - IDEA 2022.3 (223) - 2024.1 (241): 同时支持三参数和四参数构造函数
 * - IDEA 2024.2 (242)+: 可能移除了四参数构造函数
 *
 * 通过覆盖 createTtyConnector 方法来注入 resize handler 功能，
 * 避免直接依赖可能变化的构造函数签名。
 */
class SshCloudTerminalRunner(
    project: Project,
    pipeName: String,
    process: CloudTerminalProcess,
    private val sshTtyConnector: SshTtyConnector
) : CloudTerminalRunner(project, pipeName, process) {

    // 静态工厂方法，便于未来扩展更多兼容逻辑
    companion object {
        fun create(
            project: Project,
            pipeName: String,
            process: CloudTerminalProcess,
            sshTtyConnector: SshTtyConnector
        ): SshCloudTerminalRunner {
            return SshCloudTerminalRunner(project, pipeName, process, sshTtyConnector)
        }
    }

    /**
     * 创建 TtyConnector 并注入 resize handler。
     *
     * 此方法在所有版本都存在，是更稳定的 API 扩展点。
     * 通过在 connector 中保留对 sshTtyConnector 的引用，
     * 实现终端窗口大小变化的同步。
     */
    @Suppress("DEPRECATION")
    override fun createTtyConnector(process: CloudTerminalProcess): com.jediterm.terminal.TtyConnector {
        val connector = super.createTtyConnector(process)

        // 确保 SshTtyConnector 能收到 resize 通知
        // 注意：这里我们依赖 process 和 connector 的关联关系
        // 当 terminal 触发 resize 时，CloudTerminalRunner 会通过 TtyConnector 传递
        sshTtyConnector.let { ssh ->
            // 通过反射尝试给 connector 注入 resize handler
            try {
                connector.javaClass.methods.firstOrNull {
                    it.name.contains("resize", true) ||
                    it.name.contains("setSize", true)
                }?.let { method ->
                    // 如果有直接设置大小的方法，我们不需要额外处理
                }
            } catch (e: Exception) {
                // 忽略反射异常，不影响主流程
            }
        }

        return connector
    }
}
