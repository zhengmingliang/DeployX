package com.alianga.idea.filesync.ssh

import com.alianga.idea.filesync.model.ServerConfig
import com.intellij.openapi.diagnostic.Logger
import com.jcraft.jsch.ChannelShell
import com.jediterm.terminal.TtyConnector
import java.awt.Dimension
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * SSH 终端连接器 - 包装 JSch 的 ChannelShell，实现 jediterm 的 TtyConnector 接口。
 *
 * 认证在 SSH 协议层完成（由 SshConnection.connect 处理密码/密钥），因此终端连接
 * 建立后即为已登录状态，无需再在终端中输入密码。
 */
class SshTtyConnector(
    private val serverConfig: ServerConfig,
    private val channel: ChannelShell,
    private val title: String
) : TtyConnector {

    companion object {
        private val LOG = Logger.getInstance(SshTtyConnector::class.java)
    }

    private val remoteInput: InputStream = channel.inputStream
    private val remoteOutput: OutputStream = channel.outputStream
    private val reader = InputStreamReader(remoteInput, StandardCharsets.UTF_8)

    /** 供 CloudTerminalProcess 构造使用的输入流（远程 -> 本地） */
    fun getInputStream(): InputStream = remoteInput

    /** 供 CloudTerminalProcess 构造使用的输出流（本地 -> 远程） */
    fun getOutputStream(): OutputStream = remoteOutput

    override fun read(buf: CharArray, off: Int, len: Int): Int {
        return reader.read(buf, off, len)
    }

    override fun write(bytes: ByteArray) {
        remoteOutput.write(bytes)
        remoteOutput.flush()
    }

    override fun write(s: String) {
        write(s.toByteArray(StandardCharsets.UTF_8))
    }

    override fun isConnected(): Boolean {
        return channel.isConnected && !channel.isClosed
    }

    override fun waitFor(): Int {
        while (isConnected) {
            try {
                Thread.sleep(100L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return 0
    }

    override fun ready(): Boolean = reader.ready()

    override fun getName(): String = title

    override fun close() {
        LOG.info("Closing SSH terminal channel for ${serverConfig.displayAddress}")
        try {
            if (!channel.isClosed) {
                channel.disconnect()
            }
        } catch (e: Exception) {
            LOG.warn("Error closing channel shell", e)
        }
    }

    override fun resize(termSize: Dimension, pixelSize: Dimension) {
        try {
            channel.setPtySize(termSize.width, termSize.height, pixelSize.width, pixelSize.height)
        } catch (e: Exception) {
            LOG.debug("Terminal resize failed", e)
        }
    }

    override fun resize(termSize: Dimension) {
        resize(termSize, Dimension(0, 0))
    }
}
