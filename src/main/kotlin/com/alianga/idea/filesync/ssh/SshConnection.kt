package com.alianga.idea.filesync.ssh

import com.alianga.idea.filesync.config.FileSyncSettings
import com.alianga.idea.filesync.model.ServerConfig
import com.intellij.openapi.diagnostic.Logger
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * SSH 连接封装 - 基于 JSch 实现
 */
class SshConnection(private val serverConfig: ServerConfig) {

    companion object {
        private val LOG = Logger.getInstance(SshConnection::class.java)
        private val jsch = JSch()
    }

    private var session: Session? = null

    /**
     * 建立 SSH 连接
     */
    fun connect(): Boolean {
        return try {
            val session = jsch.getSession(serverConfig.user, serverConfig.host, serverConfig.port)

            // 配置认证方式
            when (serverConfig.authType) {
                ServerConfig.AuthType.PASSWORD -> {
                    session.setPassword(serverConfig.password)
                }
                ServerConfig.AuthType.KEY -> {
                    if (serverConfig.keyFile.isNotEmpty()) {
                        jsch.addIdentity(serverConfig.keyFile)
                    }
                }
            }

            // SSH 配置
            val config = Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("ConnectTimeout", FileSyncSettings.getInstance().connectTimeout.toString())
            }
            session.setConfig(config)
            session.timeout = FileSyncSettings.getInstance().connectTimeout

            session.connect()
            this.session = session
            LOG.info("SSH connected to ${serverConfig.displayAddress}")
            true
        } catch (e: Exception) {
            LOG.error("SSH connection failed to ${serverConfig.displayAddress}", e)
            false
        }
    }

    /**
     * 执行远程命令
     */
    fun executeCommand(command: String): CommandResult {
        val session = this.session
            ?: return CommandResult(false, "", "SSH session not connected", -1)

        return try {
            val channel = session.openChannel("exec") as ChannelExec
            // 使用 login shell 执行命令，加载 .bashrc/.bash_profile 中的别名和快捷命令
            val escapedCommand = command.replace("'", "'\\''")
            channel.setCommand("bash -l -c '$escapedCommand'")

            val outputStream = ByteArrayOutputStream()
            val errorStream = ByteArrayOutputStream()
            channel.outputStream = outputStream
            channel.setErrStream(errorStream)

            channel.connect()

            // 等待命令执行完成
            while (!channel.isClosed) {
                Thread.sleep(100)
            }

            val exitCode = channel.exitStatus
            val output = outputStream.toString("UTF-8")
            val error = errorStream.toString("UTF-8")

            channel.disconnect()

            CommandResult(exitCode == 0, output, error, exitCode)
        } catch (e: Exception) {
            LOG.error("Command execution failed: $command", e)
            CommandResult(false, "", e.message ?: "Unknown error", -1)
        }
    }

    /**
     * 测试连接
     */
    fun testConnection(): ConnectionTestResult {
        return try {
            if (!connect()) {
                return ConnectionTestResult(false, "无法连接到 ${serverConfig.displayAddress}")
            }
            val result = executeCommand("echo 'connected'")
            if (result.success) {
                ConnectionTestResult(true, "连接成功: ${serverConfig.displayAddress}")
            } else {
                ConnectionTestResult(false, "连接失败: ${result.error}")
            }
        } catch (e: Exception) {
            ConnectionTestResult(false, "连接异常: ${e.message}")
        } finally {
            disconnect()
        }
    }

    /**
     * 打开 SFTP 通道。调用方负责 disconnect channel。
     */
    fun openSftpChannel(): ChannelSftp {
        val session = this.session ?: throw IllegalStateException("SSH session not connected")
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect()
        return channel
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        session?.disconnect()
        session = null
        LOG.info("SSH disconnected from ${serverConfig.displayAddress}")
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = session?.isConnected == true

    data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String,
        val exitCode: Int
    )

    data class ConnectionTestResult(
        val success: Boolean,
        val message: String
    )
}
