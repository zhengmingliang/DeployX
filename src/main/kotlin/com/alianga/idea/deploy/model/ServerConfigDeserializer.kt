package com.alianga.idea.deploy.model

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.lang.reflect.Type

/**
 * ServerConfig 的健壮反序列化器。
 *
 * 背景：1.0.3 给 ServerConfig 新增了 `group`/`tags` 字段，类型为非空 `String`/`List<String>`
 * 并带有 Kotlin 默认值（`""` / `emptyList()`）。但 Gson 反序列化不触发 Kotlin 默认参数机制，
 * 对 JSON 中缺失的字段会直接赋 `null`，破坏非空契约——随后调用 `copy()` 即抛
 * `NullPointerException: Parameter specified as non-null is null: ... parameter group`，
 * 导致 1.0.3 升级后旧 servers.json 加载失败、服务器列表为空。
 *
 * 本反序列化器对所有可空缺失字段做兜底，保证反序列化后的 ServerConfig 满足非空约束。
 * 同时兼容旧版 JSON 中可能缺失的字段（port/auth_type/password/key_file/is_default 等）。
 */
object ServerConfigDeserializer : JsonDeserializer<ServerConfig> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): ServerConfig {
        val obj = json.asJsonObject

        fun str(name: String, default: String = ""): String =
            obj.nullSafeString(name) ?: default

        fun bool(name: String, default: Boolean): Boolean =
            obj.nullSafeBool(name) ?: default

        fun int(name: String, default: Int): Int =
            obj.nullSafeInt(name) ?: default

        val tags = obj.nullSafeStringList("tags") ?: emptyList()

        val authType = str("auth_type", "password").let { value ->
            ServerConfig.AuthType.entries.firstOrNull { it.value == value } ?: ServerConfig.AuthType.PASSWORD
        }

        return ServerConfig(
            id = str("id"),
            name = str("name"),
            host = str("host"),
            port = int("port", 22),
            user = str("user"),
            authType = authType,
            password = str("password"),
            keyFile = str("key_file"),
            isDefault = bool("is_default", false),
            group = str("group"),
            tags = tags
        )
    }

    private fun JsonObject.nullSafeString(name: String): String? =
        if (has(name) && !get(name).isJsonNull) get(name).asString else null

    private fun JsonObject.nullSafeBool(name: String): Boolean? =
        if (has(name) && !get(name).isJsonNull) get(name).asBoolean else null

    private fun JsonObject.nullSafeInt(name: String): Int? =
        if (has(name) && !get(name).isJsonNull) get(name).asInt else null

    private fun JsonObject.nullSafeStringList(name: String): List<String>? =
        if (has(name) && !get(name).isJsonNull) {
            get(name).asJsonArray.mapNotNull { el ->
                if (el.isJsonNull) null else el.asString
            }
        } else null
}
