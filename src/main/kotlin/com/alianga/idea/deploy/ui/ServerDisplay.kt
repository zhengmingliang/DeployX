package com.alianga.idea.deploy.ui

/**
 * 服务器在下拉 / 列表中的显示文本（纯函数，便于单测）。
 *
 * 解决现状问题 #4「164 - 164」：id 与名称相同时只显示一次；不同时用「id · 名称」。
 *
 * @author 郑明亮
 */
object ServerDisplay {
    fun title(id: String, name: String): String =
        if (name.isBlank() || name.trim() == id.trim()) id else "$id · $name"

    fun address(user: String, host: String, port: Int): String = "$user@$host:$port"
}
