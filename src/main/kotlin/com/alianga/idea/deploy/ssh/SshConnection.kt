package com.alianga.idea.deploy.ssh

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.FileSyncSettings
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.DeployCancelToken
import com.alianga.idea.deploy.service.DeployCancelledException
import com.intellij.openapi.diagnostic.Logger
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * SSH 连接封装 - 基于 JSch 实现
 */
class SshConnection(private val serverConfig: ServerConfig) {

    companion object {
        private val LOG = Logger.getInstance(SshConnection::class.java)
        /** 单次远程命令在内存中保留的最大输出字节数（512 KB），防止超大日志引发 IDE OOM */
        internal const val MAX_OUTPUT_BYTES = 512 * 1024
        /** 默认远程命令执行超时时间（5 分钟） */
        const val DEFAULT_COMMAND_TIMEOUT_MS = 300_000L
    }

    private var session: Session? = null

    /**
     * 建立 SSH 连接（带重试机制）
     */
    fun connect(): Boolean {
        return connectWithRetry().success
    }

    /**
     * 建立 SSH 连接，返回详细结果（带重试机制）
     */
    fun connectWithDetails(): ConnectResult {
        return connectWithRetry()
    }

    /**
     * 建立 SSH 连接，支持重试
     * 针对服务器 SSH 连接限制（如 MaxStartups）或网络波动进行自动重试
     */
    private fun connectWithRetry(maxRetries: Int = 3, retryDelayMs: Long = 2000): ConnectResult {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                // 每次连接使用独立的 JSch 实例，避免多服务器并发部署时共享 identity 列表引发线程安全与密钥混用问题
                val jsch = JSch()
                val session = jsch.getSession(serverConfig.user, serverConfig.host, serverConfig.port)
                val timeout = FileSyncSettings.getInstance().connectTimeout

                // 配置认证方式
                when (serverConfig.authType) {
                    ServerConfig.AuthType.PASSWORD -> {
                        session.setPassword(serverConfig.password?.toByteArray(charset("UTF-8")))
                    }
                    ServerConfig.AuthType.KEY -> {
                        if (serverConfig.keyFile.isNotEmpty()) {
                            jsch.addIdentity(serverConfig.keyFile)
                        }
                    }
                }

                // SSH 配置 - 优化 mwiede JSch 2.28.3 的兼容性
                val config = Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    // 连接超时（毫秒）- 用于 socket 连接阶段
                    put("ConnectTimeout", timeout.toString())
                    // 会话超时（毫秒）- 用于读取超时
                    put("timeout", timeout.toString())
                    // 优先认证方式顺序 - 避免不必要的尝试
                    put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                    // 服务端 Alive 检查 - 防止连接被防火墙断开
                    put("ServerAliveInterval", "30000")
                    put("ServerAliveCountMax", "3")
                }
                session.setConfig(config)
                // 全局超时设置
                session.timeout = timeout

                if (attempt > 1) {
                    LOG.info("Connecting to ${serverConfig.displayAddress} (attempt $attempt/$maxRetries)...")
                } else {
                    LOG.info("Connecting to ${serverConfig.displayAddress} with timeout ${timeout}ms...")
                }

                session.connect()
                this.session = session
                LOG.info("SSH connected to ${serverConfig.displayAddress}")
                return ConnectResult(true)
            } catch (e: Exception) {
                lastException = e
                val errorMsg = "SSH connection attempt $attempt failed to ${serverConfig.displayAddress}: ${e.message}"
                LOG.warn(errorMsg, e)

                if (attempt < maxRetries) {
                    LOG.info("Retrying in ${retryDelayMs}ms...")
                    Thread.sleep(retryDelayMs)
                }
            }
        }

        // 所有重试都失败
        val finalErrorMsg = "SSH connection failed after $maxRetries attempts to ${serverConfig.displayAddress}: ${lastException?.message}"
        val exceptionClass = lastException?.javaClass?.simpleName
        LOG.error(finalErrorMsg, lastException)
        return ConnectResult(false, finalErrorMsg, exceptionClass)
    }

    /**
     * 执行远程命令（支持超时控制、取消中断与实时流式输出回调）。
     *
     * @param command 待执行的 Shell 命令（经 `bash -l -c` 执行）
     * @param timeoutMs 超时时间（毫秒）；`<= 0` 或 `null` 表示不限制超时
     * @param cancelToken 取消令牌；当检测到已取消时立即断开 channel 并抛出 [DeployCancelledException]
     * @param onOutputLine 标准输出实时行回调（每读到一行完整文本或结束时剩余文本触发一次）
     * @param onErrorLine 标准错误实时行回调
     */
    fun executeCommand(
        command: String,
        timeoutMs: Long? = DEFAULT_COMMAND_TIMEOUT_MS,
        cancelToken: DeployCancelToken? = null,
        onOutputLine: ((String) -> Unit)? = null,
        onErrorLine: ((String) -> Unit)? = null
    ): CommandResult {
        val session = this.session
            ?: return CommandResult(false, "", "SSH session not connected", -1)

        cancelToken?.throwIfCancelled()

        var channel: ChannelExec? = null
        return try {
            val ch = session.openChannel("exec") as ChannelExec
            channel = ch
            // 使用 login shell 执行命令，加载 .bashrc/.bash_profile 中的别名和快捷命令
            val escapedCommand = command.replace("'", "'\\''")
            ch.setCommand("bash -l -c '$escapedCommand'")

            val stdoutStream = ch.inputStream
            val stderrStream = ch.errStream

            ch.connect()

            val stdoutCollector = BoundedLineCollector(MAX_OUTPUT_BYTES, onOutputLine)
            val stderrCollector = BoundedLineCollector(MAX_OUTPUT_BYTES, onErrorLine)
            val buf = ByteArray(4096)
            val startTime = System.currentTimeMillis()

            // 循环非阻塞流式读取 stdout/stderr，同时检查取消令牌与超时
            while (true) {
                if (cancelToken?.isCancelled() == true) {
                    runCatching { ch.disconnect() }
                    throw DeployCancelledException()
                }

                val readAny = drainAvailable(stdoutStream, stdoutCollector, buf) or
                        drainAvailable(stderrStream, stderrCollector, buf)

                if (ch.isClosed) {
                    // channel 关闭后把缓冲区剩余字节全部读完
                    drainRemaining(stdoutStream, stdoutCollector, buf)
                    drainRemaining(stderrStream, stderrCollector, buf)
                    break
                }

                if (timeoutMs != null && timeoutMs > 0) {
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= timeoutMs) {
                        runCatching { ch.disconnect() }
                        stdoutCollector.finish()
                        stderrCollector.finish()
                        val timeoutSec = (timeoutMs / 1000).coerceAtLeast(1)
                        val timeoutMsg = "Command timed out after ${timeoutSec}s"
                        onErrorLine?.invoke("[TIMEOUT] $timeoutMsg")
                        val errText = stderrCollector.getText()
                        val combinedErr = if (errText.isBlank()) timeoutMsg else "$errText\n$timeoutMsg"
                        return CommandResult(
                            success = false,
                            output = stdoutCollector.getText(),
                            error = combinedErr,
                            exitCode = -1,
                            timedOut = true
                        )
                    }
                }

                if (!readAny) {
                    Thread.sleep(30)
                }
            }

            stdoutCollector.finish()
            stderrCollector.finish()

            val exitCode = ch.exitStatus
            val output = stdoutCollector.getText()
            val error = stderrCollector.getText()

            ch.disconnect()

            CommandResult(exitCode == 0, output, error, exitCode)
        } catch (e: DeployCancelledException) {
            runCatching { channel?.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { channel?.disconnect() }
            LOG.error("Command execution failed: $command", e)
            CommandResult(false, "", e.message ?: "Unknown error", -1)
        }
    }

    /** 非阻塞读取当前可用字节 */
    private fun drainAvailable(
        stream: InputStream?,
        collector: BoundedLineCollector,
        buf: ByteArray
    ): Boolean {
        if (stream == null) return false
        var readAny = false
        while (stream.available() > 0) {
            val n = stream.read(buf, 0, buf.size)
            if (n <= 0) break
            collector.append(buf, n)
            readAny = true
        }
        return readAny
    }

    /** 通道关闭后排空剩余流数据 */
    private fun drainRemaining(
        stream: InputStream?,
        collector: BoundedLineCollector,
        buf: ByteArray
    ) {
        if (stream == null) return
        try {
            while (stream.available() > 0) {
                val n = stream.read(buf, 0, buf.size)
                if (n <= 0) break
                collector.append(buf, n)
            }
        } catch (_: Exception) {
            // 忽略关闭后的流读取异常
        }
    }

    /**
     * 带容量上限与实时行回调的 UTF-8 输出收集器。
     * 既能在流式读取过程中逐行触发 [onLine] 回调，又能限制内存中保留的最大字节数防止 OOM。
     */
    internal class BoundedLineCollector(
        private val maxBytes: Int,
        private val onLine: ((String) -> Unit)? = null
    ) {
        private val fullBuffer = ByteArrayOutputStream()
        private val lineBuffer = ByteArrayOutputStream()
        private var truncated = false

        fun append(bytes: ByteArray, length: Int) {
            val remaining = maxBytes - fullBuffer.size()
            if (remaining > 0) {
                val toWrite = minOf(length, remaining)
                fullBuffer.write(bytes, 0, toWrite)
                if (toWrite < length) truncated = true
            } else if (length > 0) {
                truncated = true
            }

            if (onLine != null) {
                for (i in 0 until length) {
                    val b = bytes[i]
                    if (b == '\n'.code.toByte()) {
                        emitLine()
                    } else if (b != '\r'.code.toByte()) {
                        if (lineBuffer.size() < 16 * 1024) {
                            lineBuffer.write(b.toInt())
                        }
                    }
                }
            }
        }

        fun finish() {
            if (onLine != null && lineBuffer.size() > 0) {
                emitLine()
            }
        }

        private fun emitLine() {
            val line = lineBuffer.toString(StandardCharsets.UTF_8)
            lineBuffer.reset()
            onLine?.invoke(line)
        }

        fun getText(): String {
            val base = fullBuffer.toString(StandardCharsets.UTF_8)
            return if (truncated) "$base\n...[output truncated]..." else base
        }
    }

    /**
     * 测试连接
     */
    fun testConnection(): ConnectionTestResult {
        return try {
            if (!connect()) {
                return ConnectionTestResult(false, DeployXBundle.message("ssh.connection.connectFailed", serverConfig.displayAddress))
            }
            val result = executeCommand("echo 'connected'")
            if (result.success) {
                ConnectionTestResult(true, DeployXBundle.message("ssh.connection.connectSuccess", serverConfig.displayAddress))
            } else {
                ConnectionTestResult(false, DeployXBundle.message("ssh.connection.connectFailedWith", result.error))
            }
        } catch (e: Exception) {
            ConnectionTestResult(false, DeployXBundle.message("ssh.connection.connectException", e.message ?: ""))
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
     * 打开 Shell 通道，用于交互式终端
     * @param termType 终端类型，如 xterm, vt100, ansi 等
     */
    fun openShellChannel(termType: String = "xterm"): ChannelShell {
        val session = this.session ?: throw IllegalStateException("SSH session not connected")
        val channel = session.openChannel("shell") as ChannelShell
        channel.setPty(true)  // 启用伪终端
        channel.setPtyType(termType)
        channel.connect()
        LOG.info("Shell channel opened: $termType for ${serverConfig.displayAddress}")
        return channel
    }

    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = session?.isConnected == true

    data class CommandResult(
        val success: Boolean,
        val output: String,
        val error: String,
        val exitCode: Int,
        val timedOut: Boolean = false
    )

    data class ConnectionTestResult(
        val success: Boolean,
        val message: String
    )

    /**
     * SSH 连接结果，包含详细错误信息
     */
    data class ConnectResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val exceptionClass: String? = null
    )
}
