package com.alianga.idea.filesync.service

import com.alianga.idea.filesync.model.ServerConfig
import com.alianga.idea.filesync.ssh.SshCloudTerminalProcess
import com.alianga.idea.filesync.ssh.SshCloudTerminalRunner
import com.alianga.idea.filesync.ssh.SshConnection
import com.alianga.idea.filesync.ssh.SshTtyConnector
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import org.jetbrains.plugins.terminal.TerminalTabState
import org.jetbrains.plugins.terminal.TerminalView

/**
 * 终端服务 - 通过建立真实的 SSH 连接（协议层认证，免密）打开 IDEA 终端。
 *
 * 流程参考 easy-deploy：用 JSch 建立 SSH 连接并打开 Shell channel，
 * 包装成 CloudTerminalProcess/CloudTerminalRunner 后通过 TerminalView.createNewSession
 * 创建终端会话。认证在 SSH 协议层完成，终端连接建立后即为已登录状态。
 */
class TerminalService : Disposable {

    companion object {
        private val LOG = Logger.getInstance(TerminalService::class.java)

        fun getInstance(): TerminalService =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(TerminalService::class.java)
    }

    /**
     * 为指定服务器打开 SSH 终端（免密，认证在协议层完成）。
     */
    fun openTerminal(project: Project, serverConfig: ServerConfig): Boolean {
        LOG.info("========================================")
        LOG.info("Opening SSH terminal (password-free) for server: ${serverConfig.name} (${serverConfig.displayAddress})")
        LOG.info("========================================")

        val title = "SSH: ${serverConfig.name} - ${serverConfig.user}@${serverConfig.host}"
        val pipeName = "${serverConfig.host}:${serverConfig.port}"

        // SSH 连接是阻塞操作，放在后台线程执行；createNewSession 必须在 EDT
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Opening SSH terminal to ${serverConfig.displayAddress}...", true) {
            private var opened = false
            private var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Connecting to ${serverConfig.displayAddress}..."

                try {
                    // 1. 建立 SSH 连接（密码/密钥认证在此完成 = 免密基础）
                    val connection = SshConnection(serverConfig)
                    val connectResult = connection.connectWithDetails()
                    if (!connectResult.success) {
                        errorMessage = connectResult.errorMessage ?: "SSH connection failed"
                        LOG.error("❌ SSH connection failed: ${connectResult.errorMessage}")
                        return
                    }
                    indicator.fraction = 0.5

                    // 2. 打开 Shell channel（已启用 PTY）
                    indicator.text = "Opening shell channel..."
                    val channel = connection.openShellChannel()
                    indicator.fraction = 0.8

                    // 3. 构建 TtyConnector + CloudTerminal 适配
                    val ttyConnector = SshTtyConnector(serverConfig, channel, title)
                    val cloudProcess = SshCloudTerminalProcess(ttyConnector)
                    val runner = SshCloudTerminalRunner(project, pipeName, cloudProcess, ttyConnector)

                    // 4. 在 EDT 创建终端会话
                    opened = true
                    indicator.fraction = 1.0

                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        createTerminalSession(project, runner, title)
                    }
                } catch (e: Exception) {
                    LOG.error("❌ Failed to open SSH terminal", e)
                    errorMessage = e.message ?: "Unknown error"
                }
            }

            override fun onFinished() {
                if (opened) {
                    LOG.info("✅ SSH terminal opened successfully for: ${serverConfig.displayAddress}")
                } else {
                    LOG.warn("❌ SSH terminal opening failed")
                    val msg = errorMessage ?: "无法连接到服务器 ${serverConfig.displayAddress}"
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, msg, "SSH 终端")
                    }
                }
                LOG.info("========================================")
            }
        })

        return true
    }

    /**
     * 在 IDEA 终端工具窗口中创建新会话。
     */
    private fun createTerminalSession(
        project: Project,
        runner: SshCloudTerminalRunner,
        title: String
    ) {
        try {
            val terminalView = TerminalView.getInstance(project)
            val tabState = TerminalTabState().apply {
                myTabName = title
            }
            terminalView.createNewSession(runner, tabState)
            LOG.info("✅ Terminal session created: $title")
        } catch (e: Exception) {
            LOG.error("❌ Failed to create terminal session", e)
        }
    }

    override fun dispose() {
        LOG.info("TerminalService disposed")
    }
}
