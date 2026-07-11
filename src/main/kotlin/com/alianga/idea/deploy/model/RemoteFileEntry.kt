package com.alianga.idea.deploy.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程文件条目 - 描述远程服务器上的一个文件或目录。
 *
 * 用于「远程文件浏览器」功能，承载 SFTP 列目录返回的元数据。
 */
data class RemoteFileEntry(
    /** 条目名称（不含路径） */
    val name: String,
    /** 绝对路径 */
    val path: String,
    /** 是否为目录 */
    val isDirectory: Boolean,
    /** 是否为符号链接 */
    val isLink: Boolean = false,
    /** 字节数（目录为 0） */
    val size: Long = 0L,
    /** 最后修改时间戳（毫秒） */
    val modifiedTime: Long = 0L
) {
    /** 人类可读的文件大小，目录显示 "-" */
    val displaySize: String
        get() = if (isDirectory) "-" else formatSize(size)

    /** 人类可读的修改时间，无时间戳则显示 "-" */
    val displayModified: String
        get() = if (modifiedTime <= 0L) "-" else DATE_FORMAT.format(Date(modifiedTime))

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        /** 将字节数格式化为带单位的可读字符串 */
        fun formatSize(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024.0
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.size - 1) {
                value /= 1024.0
                unitIndex++
            }
            return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
        }
    }
}
