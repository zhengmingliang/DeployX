package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.model.RemoteFileEntry
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.ssh.SshConnection
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程文件浏览器服务。
 *
 * 提供基于 SFTP 的远程文件操作能力（列目录、读/写文件内容、下载、新建/删除目录、删除文件），
 * 通过 [BrowserSession] 持有一个可复用的 SSH 连接与 SFTP 通道，避免每次操作都重新建立连接，
 * 显著降低远程浏览的延迟。
 *
 * 调用方通过 [openSession] 获取会话，使用完毕后必须调用 [BrowserSession.close] 释放资源
 * （通常在对话框 dispose 时调用）。
 */
@Service
class RemoteFileBrowserService {

    /** 为指定服务器打开一个浏览会话 */
    fun openSession(server: ServerConfig): BrowserSession = BrowserSession(server)

    // ===== 远程文件在 IDE 主编辑器中打开 + 保存回写 =====

    /** 远程编辑文件句柄，关联一个本地临时文件路径与远程路径 */
    data class RemoteFileHandle(val serverId: String, val remotePath: String, val fileName: String, val localPath: String)

    /** 当前在 IDE 编辑器中打开的远程文件：本地临时文件路径 -> 句柄 */
    private val openRemoteFiles = ConcurrentHashMap<String, RemoteFileHandle>()

    /** 编辑器保存用的会话缓存（serverId -> session），避免每次 Ctrl+S 都重连 */
    private val editorSessions = ConcurrentHashMap<String, BrowserSession>()

    /** 本地临时文件根目录 */
    private val editorTempRoot: File by lazy {
        File(System.getProperty("user.home"), ".deploy-x/editor").also { it.mkdirs() }
    }

    /** 持有 messageBus 连接防止 GC 回收导致监听器失效 */
    @Suppress("FieldCanBeLocal")
    private val messageBusConnection = ApplicationManager.getApplication().messageBus.connect()

    init {
        // 监听 IDE 文档保存：当保存的是远程文件时，将内容写回服务器
        messageBusConnection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val vfile = FileDocumentManager.getInstance().getFile(document) ?: return
                val handle = openRemoteFiles[vfile.path] ?: return
                saveRemoteFile(handle, document.text)
            }
        })
    }

    /**
     * 在 IDE 主编辑器中打开远程文件（支持语法高亮），编辑后 Ctrl+S 自动写回远程服务器。
     *
     * 将远程内容写入本地临时文件（~/.deploy-x/editor/{serverId}/{fileName}），
     * 再通过 LocalFileSystem 刷新后在 IDE 编辑器中打开。使用真实文件可确保
     * IDE 的保存管线（Ctrl+S → beforeDocumentSaving）正常触发。
     */
    fun openInEditor(project: Project, server: ServerConfig, remotePath: String, fileName: String) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading remote file: $fileName", true) {
                override fun run(indicator: ProgressIndicator) {
                    var content: String? = null
                    var error: String? = null
                    try {
                        indicator.checkCanceled()
                        val session = getEditorSession(server)
                        content = session.readFileText(remotePath)
                    } catch (e: ProcessCanceledException) {
                        return
                    } catch (e: Exception) {
                        LOG.warn("Failed to read remote file for editor: $remotePath", e)
                        error = e.message ?: e.toString()
                    }

                    val loadedContent = content
                    ApplicationManager.getApplication().invokeLater {
                        if (loadedContent == null) {
                            notify(project, error ?: "Unknown error", NotificationType.ERROR)
                            return@invokeLater
                        }
                        val size = loadedContent.toByteArray(Charsets.UTF_8).size.toLong()
                        if (size > MAX_EDITOR_SIZE) {
                            notify(project, "File is too large (${RemoteFileEntry.formatSize(size)}) to open in editor. Use download instead.", NotificationType.WARNING)
                            return@invokeLater
                        }
                        openTempFileInEditor(project, server, remotePath, fileName, loadedContent)
                    }
                }
            }
        )
    }

    /** 将内容写入本地临时文件，刷新 VFS 后在编辑器中打开 */
    private fun openTempFileInEditor(
        project: Project, server: ServerConfig,
        remotePath: String, fileName: String, content: String
    ) {
        try {
            // 写入本地临时文件
            val serverDir = File(editorTempRoot, server.id).also { it.mkdirs() }
            val localFile = File(serverDir, fileName)
            localFile.writeText(content, Charsets.UTF_8)
            // 刷新虚拟文件系统以获取 VirtualFile
            val vfile = VfsUtil.findFileByIoFile(localFile, true)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(localFile)
            if (vfile == null) {
                notify(project, "Cannot open temp file: ${localFile.absolutePath}", NotificationType.ERROR)
                return
            }
            // 追踪：按本地文件路径索引（而非 VirtualFile 实例）
            openRemoteFiles[vfile.path] = RemoteFileHandle(server.id, remotePath, fileName, localFile.absolutePath)
            FileEditorManager.getInstance(project).openFile(vfile, true)
        } catch (e: Exception) {
            LOG.warn("Failed to create temp file for editor", e)
            notify(project, "Failed to open remote file: ${e.message}", NotificationType.ERROR)
        }
    }

    /** 将远程文件内容写回服务器（Ctrl+S 触发，后台执行） */
    private fun saveRemoteFile(handle: RemoteFileHandle, content: String) {
        val server = ServerManager.getInstance().getServer(handle.serverId) ?: run {
            LOG.warn("Server not found for remote file save: ${handle.serverId}")
            return
        }
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(null, "Saving remote file: ${handle.fileName}", true) {
                override fun run(indicator: ProgressIndicator) {
                    var error: String? = null
                    try {
                        indicator.checkCanceled()
                        val session = getEditorSession(server)
                        session.writeFileText(handle.remotePath, content)
                    } catch (e: ProcessCanceledException) {
                        return
                    } catch (e: Exception) {
                        LOG.warn("Failed to save remote file: ${handle.remotePath}", e)
                        error = e.message ?: e.toString()
                    }
                    ApplicationManager.getApplication().invokeLater {
                        if (error != null) {
                            notify(null, "Failed to save remote file: $error", NotificationType.ERROR)
                        } else {
                            notify(null, "Remote file saved: ${handle.fileName}", NotificationType.INFORMATION)
                        }
                    }
                }
            }
        )
    }

    /** 获取/创建编辑器保存用的会话（按服务器缓存） */
    private fun getEditorSession(server: ServerConfig): BrowserSession {
        return editorSessions.computeIfAbsent(server.id) {
            val s = openSession(server)
            LOG.info("Created editor session for server ${server.id}")
            s
        }
    }

    private fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("DeployX")
            .createNotification(content, type)
            .notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(RemoteFileBrowserService::class.java)
        /** 允许在 IDE 编辑器中打开的最大文件大小（5MB） */
        private const val MAX_EDITOR_SIZE = 5L * 1024 * 1024

        fun getInstance(): RemoteFileBrowserService =
            ApplicationManager.getApplication().getService(RemoteFileBrowserService::class.java)
    }

    /**
     * 浏览会话 - 持有一个 SSH 连接和 SFTP 通道，可复用以减少连接开销。
     *
     * 所有操作在调用前会通过 [ensureConnected] 检查并按需重连。
     * 调用方负责在结束时调用 [close] 释放资源。
     */
    class BrowserSession internal constructor(private val server: ServerConfig) {

        private val connection = SshConnection(server)
        private var channel: ChannelSftp? = null

        /** 服务器显示名称（供 UI 使用） */
        val serverName: String get() = server.name

        /** 服务器地址（供 UI 使用） */
        val serverAddress: String get() = server.displayAddress

        /**
         * 确保连接和 SFTP 通道可用，必要时重连。
         * @return true 表示通道已就绪可用
         */
        fun ensureConnected(): Boolean {
            if (channel?.isConnected == true && connection.isConnected()) return true
            try {
                channel?.disconnect()
            } catch (_: Exception) {
                // ignore
            }
            channel = null
            if (!connection.isConnected()) {
                connection.disconnect()
                if (!connection.connect()) {
                    LOG.warn("Failed to (re)connect to ${server.displayAddress}")
                    return false
                }
            }
            channel = connection.openSftpChannel()
            return channel?.isConnected == true
        }

        /** 强制断开（重置连接状态，用于重连前清理） */
        private fun forceDisconnect() {
            try { channel?.disconnect() } catch (_: Exception) {}
            channel = null
            connection.disconnect()
        }

        /** 带一次重连重试的 SFTP 操作包装。 */
        private inline fun <T> withRetry(op: () -> T): T {
            try {
                return op()
            } catch (e: IOException) {
                LOG.info("SFTP I/O error, reconnecting to ${server.displayAddress}: ${e.message}")
                forceDisconnect()
                if (!ensureConnected()) throw e
                return op()
            }
        }

        /**
         * 列出指定目录下的条目（按目录在前、名称不区分大小写排序）。
         * @throws IOException 连接失败或目录不可读
         */
        fun listDirectory(path: String): List<RemoteFileEntry> = withRetry {
            if (!ensureConnected()) {
                throw IOException("SFTP channel not connected to ${server.displayAddress}")
            }
            val ch = channel ?: throw IOException("SFTP channel is null")
            val normalized = normalizePath(path)
            @Suppress("UNCHECKED_CAST")
            val entries = try {
                ch.ls(normalized) as Vector<ChannelSftp.LsEntry>
            } catch (e: SftpException) {
                throw IOException("Cannot list directory '$normalized': ${e.message}", e)
            }
            return entries
                .filter { it.filename != "." && it.filename != ".." }
                .map { e ->
                    val attrs = e.attrs
                    val name = e.filename
                    val absPath = joinPath(normalized, name)
                    RemoteFileEntry(
                        name = name,
                        path = absPath,
                        isDirectory = attrs.isDir,
                        isLink = attrs.isLink,
                        size = if (attrs.isDir) 0L else attrs.size,
                        modifiedTime = attrs.mTime * 1000L
                    )
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

        /**
         * 读取远程文本文件内容（UTF-8）。
         */
        fun readFileText(path: String): String = withRetry {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            val stream = ch.get(path) ?: throw IOException("Cannot open remote file: $path")
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        /**
         * 将文本内容写回远程文件（覆盖写入，UTF-8）。
         */
        fun writeFileText(path: String, content: String) = withRetry {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            val bytes = content.toByteArray(Charsets.UTF_8)
            ch.put(ByteArrayInputStream(bytes), path)
        }

        /**
         * 下载单个远程文件到本地路径（自动创建本地父目录）。
         */
        fun downloadFile(remotePath: String, localPath: String) {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            val local = Paths.get(localPath)
            Files.createDirectories(local.parent)
            ch.get(remotePath, localPath)
        }

        /**
         * 递归下载远程目录到本地目录。
         * @return (下载文件数, 总字节数)
         */
        fun downloadDirectory(remotePath: String, localDir: String): Pair<Int, Long> {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            val remoteBase = normalizePath(remotePath)
            val localBase = Paths.get(localDir)
            Files.createDirectories(localBase)
            var count = 0
            var totalSize = 0L
            // 使用栈进行迭代式广度遍历，避免递归过深导致栈溢出
            val stack = ArrayDeque<Pair<String, java.nio.file.Path>>()
            stack.addLast(remoteBase to localBase)
            while (stack.isNotEmpty()) {
                val (curRemote, curLocal) = stack.removeLast()
                @Suppress("UNCHECKED_CAST")
                val entries = try {
                    (ch.ls(curRemote) as Vector<ChannelSftp.LsEntry>)
                        .filter { it.filename != "." && it.filename != ".." }
                } catch (e: SftpException) {
                    LOG.warn("Skipping unreadable remote directory: $curRemote (${e.message})")
                    continue
                }
                for (e in entries) {
                    val attrs = e.attrs
                    val childRemote = joinPath(curRemote, e.filename)
                    val childLocal = curLocal.resolve(e.filename)
                    if (attrs.isDir) {
                        Files.createDirectories(childLocal)
                        stack.addLast(childRemote to childLocal)
                    } else {
                        Files.createDirectories(childLocal.parent)
                        ch.get(childRemote, childLocal.toString())
                        count++
                        totalSize += attrs.size
                    }
                }
            }
            return count to totalSize
        }

        /**
         * 上传单个本地文件到远程路径（自动创建远程父目录）。
         */
        fun uploadFile(localPath: String, remotePath: String) {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            val parent = remotePath.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }
            ensureRemoteDir(parent)
            ch.put(localPath, remotePath)
        }

        /**
         * 递归上传本地目录到远程目录。
         * @return (上传文件数, 总字节数)
         */
        fun uploadDirectory(localDir: String, remoteDir: String): Pair<Int, Long> {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            val localBase = Paths.get(localDir)
            val remoteBase = normalizePath(remoteDir)
            ensureRemoteDir(remoteBase)
            var count = 0
            var totalSize = 0L
            java.nio.file.Files.walk(localBase).use { stream ->
                stream.filter { !java.nio.file.Files.isDirectory(it) }.forEach { localFile ->
                    val relative = localBase.relativize(localFile).toString().replace(java.io.File.separatorChar, '/')
                    val remotePath = joinPath(remoteBase, relative)
                    val parent = remotePath.substringBeforeLast('/').ifBlank { "/" }
                    ensureRemoteDir(parent)
                    ch.put(localFile.toString(), remotePath)
                    count++
                    totalSize += java.nio.file.Files.size(localFile)
                }
            }
            return count to totalSize
        }

        /** 递归创建远程目录（忽略已存在的） */
        private fun ensureRemoteDir(remoteDir: String) {
            val normalized = remoteDir.replace("\\", "/").trimEnd('/')
            if (normalized.isBlank() || normalized == "/") return
            var current = if (normalized.startsWith('/')) "/" else ""
            normalized.trim('/').split('/').filter { it.isNotBlank() }.forEach { part ->
                current = if (current == "/" || current.isBlank()) "$current$part" else "$current/$part"
                try {
                    chSafe()?.cd(current)
                } catch (_: Exception) {
                    try {
                        chSafe()?.mkdir(current)
                    } catch (_: Exception) {
                        // 已存在或无权限，忽略
                    }
                }
            }
        }

        private fun chSafe(): ChannelSftp? = channel

        /** 新建目录 */
        fun createDirectory(path: String) {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            ch.mkdir(path)
        }

        /** 删除单个文件 */
        fun deleteFile(path: String) {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val ch = channel ?: throw IOException("SFTP channel is null")
            ch.rm(path)
        }

        /**
         * 删除目录（含非空目录）。SFTP 的 rmdir 仅能删除空目录，因此非空目录通过 `rm -rf` 递归删除。
         */
        fun deleteDirectory(path: String) {
            if (!ensureConnected()) throw IOException("SFTP channel not connected")
            val escaped = path.replace("\"", "\\\"")
            val result = connection.executeCommand("rm -rf \"$escaped\"")
            if (!result.success) {
                throw IOException("Failed to delete directory '$path': ${result.error.ifBlank { "exit code ${result.exitCode}" }}")
            }
        }

        /** 获取单个路径的元信息，不存在或不可访问时返回 null */
        fun stat(path: String): RemoteFileEntry? {
            if (!ensureConnected()) return null
            val ch = channel ?: return null
            return try {
                val attrs = ch.stat(path)
                val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
                RemoteFileEntry(
                    name = name,
                    path = path,
                    isDirectory = attrs.isDir,
                    isLink = attrs.isLink,
                    size = if (attrs.isDir) 0L else attrs.size,
                    modifiedTime = attrs.mTime * 1000L
                )
            } catch (_: Exception) {
                null
            }
        }

        /** 释放连接与通道资源 */
        fun close() {
            try {
                channel?.disconnect()
            } catch (_: Exception) {
                // ignore
            }
            channel = null
            connection.disconnect()
            LOG.info("Remote browser session closed for ${server.displayAddress}")
        }

        private fun normalizePath(path: String): String {
            val p = path.trim().replace("\\", "/")
            return if (p.isBlank()) "/" else p
        }

        private fun joinPath(base: String, name: String): String {
            val b = base.trimEnd('/')
            return if (b.isBlank() || b == "/") "/$name" else "$b/$name"
        }
    }
}
