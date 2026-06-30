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
 * 基于 SSH 的 CloudTerminalRunner。
 *
 * 复用 CloudTerminalRunner 默认实现（默认 TtyConnector 基于 CloudTerminalProcess 的输入输出流），
 * 仅通过 TtyResizeHandler 把终端窗口大小变化转发给 SshTtyConnector，进而调用 JSch 的
 * ChannelShell.setTerminalSize，实现终端 resize。
 */
class SshCloudTerminalRunner(
    project: Project,
    pipeName: String,
    process: CloudTerminalProcess,
    sshTtyConnector: SshTtyConnector
) : CloudTerminalRunner(project, pipeName, process, TtyResizeHandler { width, height ->
    sshTtyConnector.resize(Dimension(width, height))
})

/**
 * 终端 resize 回调，适配 IDEA 的 TerminalListener.TtyResizeHandler 接口。
 */
private fun interface TtyResizeHandler : TerminalListener.TtyResizeHandler {
    override fun onTtyResizeRequest(width: Int, height: Int)
}
