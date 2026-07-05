package com.alianga.idea.deploy.model

import com.alianga.idea.deploy.DeployXBundle
import com.google.gson.annotations.SerializedName

/**
 * 脚本配置 - 可复用的远程命令模板。
 */
data class ScriptConfig(
    @SerializedName("id")
    val id: String = "",

    @SerializedName("name")
    val name: String = "",

    @SerializedName("description")
    val description: String = "",

    @SerializedName("group")
    val group: String = DeployXBundle.message("script.defaultGroup"),

    @SerializedName("tags")
    val tags: List<String> = emptyList(),

    /** 绑定服务器 ID，空表示运行时选择或使用上下文服务器。 */
    @SerializedName("server_id")
    val serverId: String = "",

    /** Shell 命令模板，支持 ${'$'}{PARAM} 和 ${'$'}{server.host} 等变量。 */
    @SerializedName("command")
    val command: String = "",

    @SerializedName("params")
    val params: List<ScriptParam> = emptyList(),

    @SerializedName("working_dir")
    val workingDir: String = "",

    @SerializedName("auto_cd_remote_dir")
    val autoCdRemoteDir: Boolean = false,

    @SerializedName("confirm_before_run")
    val confirmBeforeRun: Boolean = true,

    @SerializedName("timeout_sec")
    val timeoutSec: Int = 300,

    @SerializedName("created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @SerializedName("updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @SerializedName("last_run_at")
    val lastRunAt: Long = 0,

    @SerializedName("last_run_status")
    val lastRunStatus: String = "",

    @SerializedName("run_count")
    val runCount: Int = 0,

    @SerializedName("dangerous_keywords")
    val dangerousKeywords: List<String> = DEFAULT_DANGEROUS_KEYWORDS
) {
    companion object {
        val DEFAULT_DANGEROUS_KEYWORDS = listOf(
            "rm -rf",
            "rm -fr",
            "mkfs",
            "dd if=",
            "> /dev/sd",
            "shutdown",
            "reboot",
            "halt",
            "init 0",
            "init 6"
        )

        fun generateId(): String =
            System.currentTimeMillis().toString(36) + "_" + (Math.random() * 100000).toInt().toString(36)

        fun ensureId(config: ScriptConfig): ScriptConfig {
            return if (config.id.isBlank()) config.copy(id = generateId()) else config
        }
    }

    val effectiveId: String
        get() = if (id.isBlank()) generateId() else id
}

/**
 * 脚本参数定义。
 */
data class ScriptParam(
    @SerializedName("name")
    val name: String = "",

    @SerializedName("label")
    val label: String = "",

    @SerializedName("type")
    val type: ParamType = ParamType.STRING,

    @SerializedName("required")
    val required: Boolean = false,

    @SerializedName("default_value")
    val defaultValue: String = "",

    @SerializedName("options")
    val options: List<String> = emptyList(),

    @SerializedName("description")
    val description: String = ""
) {
    enum class ParamType(val value: String) {
        @SerializedName("string")
        STRING("string"),

        @SerializedName("number")
        NUMBER("number"),

        @SerializedName("boolean")
        BOOLEAN("boolean"),

        @SerializedName("enum")
        ENUM("enum"),

        @SerializedName("path")
        PATH("path")
    }

    val displayLabel: String
        get() = label.ifBlank { name }
}

/**
 * 脚本运行上下文，用于模板变量插值。
 */
data class ScriptRunContext(
    val server: ServerConfig? = null,
    val mapping: MappingConfig? = null,
    val remoteDir: String? = null,
    val localSelectedPaths: List<String> = emptyList(),
    val projectBasePath: String? = null,
    val artifactPath: String? = null
) {
    companion object {
        val EMPTY = ScriptRunContext()
    }
}

/**
 * 脚本运行结果。
 */
data class ScriptRunResult(
    val scriptId: String,
    val scriptName: String,
    val serverId: String,
    val resolvedCommand: String,
    val success: Boolean,
    val exitCode: Int = -1,
    val output: String = "",
    val error: String = "",
    val duration: Long = 0,
    val params: Map<String, String> = emptyMap()
)
