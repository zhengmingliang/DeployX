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
    val isDefault: Boolean = false
) {
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

    val displayAddress: String
        get() = "$user@$host:$port"
}
