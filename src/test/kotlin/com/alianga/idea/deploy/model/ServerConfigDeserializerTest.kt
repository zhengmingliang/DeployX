package com.alianga.idea.deploy.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 回归测试：1.0.3 给 ServerConfig 新增 group/tags 字段后，旧的 servers.json
 * 缺失这两个字段，Gson 反序列化会塞 null，触发 NPE 导致服务器列表加载为空。
 *
 * 见 idea.log：
 *   NullPointerException: Parameter specified as non-null is null:
 *   method ServerConfig.copy, parameter group
 *   at ConfigManager.loadServers(ConfigManager.kt:306)
 */
class ServerConfigDeserializerTest {

    /** 模拟 1.0.2 及更早版本保存的 servers.json（无 group/tags 字段） */
    private val legacyJson = """
        [
          {
            "id": "164",
            "name": "164",
            "host": "172.16.18.164",
            "port": 22,
            "user": "root",
            "auth_type": "password",
            "password": "",
            "key_file": "",
            "is_default": false
          },
          {
            "id": "zml-62",
            "name": "腾讯云",
            "host": "62.234.82.149",
            "port": 22,
            "user": "root",
            "auth_type": "key",
            "password": "",
            "key_file": "/home/zml/.ssh/id_rsa",
            "is_default": true
          }
        ]
    """.trimIndent()

    private val gsonWithAdapter: Gson = GsonBuilder()
        .registerTypeAdapter(ServerConfig::class.java, ServerConfigDeserializer)
        .create()

    @Test
    fun `legacy json without group_tags deserializes without NPE`() {
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        val servers: List<ServerConfig> = gsonWithAdapter.fromJson(legacyJson, type)

        assertEquals(2, servers.size)

        val first = servers[0]
        assertEquals("164", first.id)
        assertEquals("", first.group)   // 缺失字段兜底为空串，而不是 null
        assertTrue(first.tags.isEmpty())
        // 关键：copy() 不再抛 NPE
        val copied = first.copy(password = "secret")
        assertEquals("secret", copied.password)
        assertEquals("164", copied.group)

        val second = servers[1]
        assertTrue(second.isDefault)
        assertEquals(ServerConfig.AuthType.KEY, second.authType)
    }

    @Test
    fun `copy on deserialized server does not throw NullPointerException`() {
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        val servers: List<ServerConfig> = gsonWithAdapter.fromJson(legacyJson, type)

        // 1.0.3 的崩溃点：ConfigManager.loadServers 调用 server.copy(password = pwd)
        servers.forEach { server ->
            // 这一行在修复前会抛 NPE: parameter group / tags is null
            server.copy(password = "loaded-pwd")
        }
    }

    @Test
    fun `new format json with group_tags round trips`() {
        val original = ServerConfig(
            id = "1", name = "n", host = "h", port = 2222, user = "u",
            authType = ServerConfig.AuthType.PASSWORD, password = "p",
            keyFile = "", isDefault = true,
            group = "生产环境", tags = listOf("web", "db")
        )
        val json = gsonWithAdapter.toJson(original)
        val back = gsonWithAdapter.fromJson(json, ServerConfig::class.java)
        assertEquals(original, back)
        assertEquals("生产环境", back.group)
        assertEquals(listOf("web", "db"), back.tags)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        // 极简 JSON：只包含必填字段
        val minimal = """{"id":"x","name":"y","host":"z","user":"u"}"""
        val server = gsonWithAdapter.fromJson(minimal, ServerConfig::class.java)
        assertEquals(22, server.port)
        assertEquals(ServerConfig.AuthType.PASSWORD, server.authType)
        assertEquals("", server.password)
        assertEquals("", server.keyFile)
        assertEquals(false, server.isDefault)
        assertEquals("", server.group)
        assertTrue(server.tags.isEmpty())
    }

    @Test
    fun `null json values are tolerated`() {
        // 某些工具可能写入显式 null
        val jsonWithNulls = """
            {"id":"x","name":"y","host":"z","user":"u",
             "group":null,"tags":null,"password":null}
        """.trimIndent()
        val server = gsonWithAdapter.fromJson(jsonWithNulls, ServerConfig::class.java)
        assertEquals("", server.group)
        assertTrue(server.tags.isEmpty())
        assertEquals("", server.password)
    }
}
