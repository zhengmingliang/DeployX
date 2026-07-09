package com.alianga.idea.deploy.util

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.service.ScriptManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 解析并解析命令字符串中的 ScriptRef 引用标记。
 *
 * 支持两种标记格式（均以 # 开头，在 bash 中是安全的注释）：
 * 1. 纯标记行: `# DeployX ScriptRef: {"scriptId":"...","scriptName":"...","params":{...}}`
 * 2. 带可读说明的标记行（说明行紧邻标记行上方）:
 *    `# [DeployX] 脚本引用: echo — 张三`
 *    `# DeployX ScriptRef: {"scriptId":"...","scriptName":"echo","params":{"name":"张三"}}`
 *
 * 解析逻辑会在执行前将所有 ScriptRef 标记替换为实际渲染的 shell 命令。
 */
object ScriptRefResolver {

    private val gson = Gson()
    private val scriptManager get() = ScriptManager.getInstance()

    /**
     * ScriptRef 标记的正则模式：匹配整行 `# DeployX ScriptRef: {...}`
     */
    private val SCRIPT_REF_PATTERN = Regex(
        """^\s*#\s*DeployX\s+ScriptRef:\s*(\{.*\})\s*$""",
        RegexOption.MULTILINE
    )

    /**
     * 可读说明行模式：`# [DeployX] 脚本引用: xxx` 或 `# [DeployX] Script Ref: xxx`
     * 匹配形如 `# [DeployX] ...` 的行。
     */
    private val DESCRIPTION_LINE_PATTERN = Regex(
        """^\s*#\s*\[DeployX]\s+.+\s*$""",
        RegexOption.MULTILINE
    )

    data class ScriptRefPayload(
        val scriptId: String,
        val scriptName: String,
        val params: Map<String, String>
    )

    /**
     * 解析命令字符串中的所有 ScriptRef 标记，将其替换为渲染后的实际命令。
     *
     * @param command 可能包含 ScriptRef 标记的命令字符串
     * @param server 目标服务器配置（用于构建 ScriptRunContext）
     * @param remoteDir 远程目录（用于构建 ScriptRunContext）
     * @return 解析后的命令字符串。如无标记，返回原字符串。
     */
    fun resolve(command: String, server: ServerConfig?, remoteDir: String?): String {
        if (command.isBlank()) return command

        val markers = SCRIPT_REF_PATTERN.findAll(command).toList()
        if (markers.isEmpty()) return command

        val context = ScriptRunContext(server = server, remoteDir = remoteDir)
        val result = StringBuilder()

        // 用于跟踪是否刚处理过一个 ScriptRef 行（用于跳过其关联的说明行）
        val processedPositions = mutableSetOf<IntRange>()

        markers.forEach { match ->
            val payload = try {
                gson.fromJson<ScriptRefPayload>(
                    match.groupValues[1],
                    object : TypeToken<ScriptRefPayload>() {}.type
                )
            } catch (e: Exception) {
                // 无法解析的 JSON — 保持原样
                return@forEach
            }

            val resolvedCommand = resolveRef(payload, context)
            processedPositions.add(match.range)
        }

        // 构建最终结果：逐个替换标记
        // 先按位置排序，从后往前替换避免偏移问题
        val replacements = markers.mapNotNull { match ->
            val payload = try {
                gson.fromJson<ScriptRefPayload>(
                    match.groupValues[1],
                    object : TypeToken<ScriptRefPayload>() {}.type
                )
            } catch (e: Exception) {
                null
            }

            if (payload == null) {
                null
            } else {
                val resolvedCommand = resolveRef(payload, context)
                match.range to resolvedCommand
            }
        }.sortedByDescending { it.first.first }

        var resolved = command
        for ((range, replacement) in replacements) {
            resolved = resolved.substring(0, range.first) + replacement + resolved.substring(range.last + 1)
        }

        return resolved
    }

    private fun resolveRef(payload: ScriptRefPayload, context: ScriptRunContext): String {
        val script = scriptManager.getScript(payload.scriptId)
        if (script == null) {
            // 脚本已删除 — 替换为错误提示注释 + echo 警告
            val warning = DeployXBundle.message(
                "script.ref.resolver.notFound",
                payload.scriptName,
                payload.scriptId
            )
            return "# ### $warning\necho \"$warning\" >&2"
        }

        return try {
            scriptManager.renderCommand(script, payload.params, context)
        } catch (e: Exception) {
            val error = DeployXBundle.message(
                "script.ref.resolver.renderFailed",
                payload.scriptName,
                e.message ?: ""
            )
            "# ### $error\necho \"$error\" >&2"
        }
    }
}
