package com.alianga.idea.deploy.model

import com.google.gson.annotations.SerializedName

/**
 * 服务器配置
 */
data class ServerConfig(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("host")
    val host: String,

    @SerializedName("port")
    val port: Int = 22,

    @SerializedName("user")
    val user: String,

    @SerializedName("auth_type")
    val authType: AuthType = AuthType.PASSWORD,

    @SerializedName("password")
    val password: String = "",

    @SerializedName("key_file")
    val keyFile: String = "",

    @SerializedName("is_default")
    val isDefault: Boolean = false,

    @SerializedName("group")
    val group: String = "",

    @SerializedName("tags")
    val tags: List<String> = emptyList(),

    /**
     * 服务器类型：SSH（远程）或 LOCAL（本地目录，用于投产增量文件更新等场景）。
     * 兼容旧配置：缺失时默认为 SSH。
     */
    @SerializedName("type")
    val type: ServerType = ServerType.SSH
) {
    /**
     * 服务器类型。
     * - [ServerType.SSH]：远程服务器，通过 SSH/rsync/SFTP 传输。
     * - [ServerType.LOCAL]：本地目录，直接本地文件拷贝（无需 SSH），用于投产增量文件更新场景。
     */
    enum class ServerType(val value: String) {
        @SerializedName("ssh")
        SSH("ssh"),

        @SerializedName("local")
        LOCAL("local");

        companion object {
            fun fromValue(value: String): ServerType =
                entries.firstOrNull { it.value == value } ?: SSH
        }
    }

    enum class AuthType(val value: String) {
        @SerializedName("password")
        PASSWORD("password"),

        @SerializedName("key")
        KEY("key");

        companion object {
            fun fromValue(value: String): AuthType =
                entries.firstOrNull { it.value == value } ?: PASSWORD
        }
    }

    /** 是否为本地服务器（无需 SSH） */
    val isLocal: Boolean
        get() = type == ServerType.LOCAL

    /**
     * 展示地址：SSH 类型显示 user@host:port；LOCAL 类型显示 [local] 标记，便于在列表/日志中区分。
     */
    val displayAddress: String
        get() = if (isLocal) "[local] $name" else "$user@$host:$port"
}
