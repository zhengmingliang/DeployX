package com.alianga.idea.deploy.service

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.config.ConfigManager
import com.alianga.idea.deploy.model.HistoryRecord
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.model.ScriptConfig
import com.alianga.idea.deploy.model.ScriptParam
import com.alianga.idea.deploy.model.ScriptRunContext
import com.alianga.idea.deploy.model.ScriptRunResult
import com.alianga.idea.deploy.model.ServerConfig
import com.alianga.idea.deploy.ssh.SshConnection
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.UUID

/**
 * 脚本管理器 - 负责脚本配置、模板渲染和远程执行。
 */
@Service
class ScriptManager {

    companion object {
        private val LOG = Logger.getInstance(ScriptManager::class.java)
        private const val MAX_HISTORY_TEXT_LENGTH = 12000

        fun getInstance(): ScriptManager =
            ApplicationManager.getApplication().getService(ScriptManager::class.java)
    }

    private val scripts = mutableListOf<ScriptConfig>()

    init {
        loadFromConfig()
    }

    private fun loadFromConfig() {
        scripts.clear()
        val loaded = ConfigManager.getInstance().loadScripts().map { ScriptConfig.ensureId(it) }
        scripts.addAll(loaded)
        if (loaded.any { it.id.isBlank() }) saveToConfig()
        LOG.info("Loaded ${scripts.size} scripts from config")
    }

    fun getScripts(): List<ScriptConfig> = scripts.toList()

    fun getScript(id: String): ScriptConfig? = scripts.firstOrNull { it.id == id }

    fun searchScripts(keyword: String = "", group: String = "", tag: String = ""): List<ScriptConfig> {
        val kw = keyword.trim().lowercase()
        return scripts.filter { script ->
            val matchKeyword = kw.isBlank() ||
                    script.name.lowercase().contains(kw) ||
                    script.description.lowercase().contains(kw) ||
                    script.command.lowercase().contains(kw) ||
                    script.tags.any { it.lowercase().contains(kw) }
            val matchGroup = group.isBlank() || group == DeployXBundle.message("script.allGroups") || script.group == group
            val matchTag = tag.isBlank() || tag == DeployXBundle.message("script.allGroups") || script.tags.contains(tag)
            matchKeyword && matchGroup && matchTag
        }
    }

    fun getGroups(): List<String> = scripts.map { it.group.ifBlank { DeployXBundle.message("script.defaultGroup") } }.distinct().sorted()

    fun getAllTags(): List<String> = scripts.flatMap { it.tags }.distinct().sorted()

    fun addScript(config: ScriptConfig): ScriptConfig {
        val now = System.currentTimeMillis()
        val withId = ScriptConfig.ensureId(config).copy(createdAt = config.createdAt.takeIf { it > 0 } ?: now, updatedAt = now)
        scripts.add(withId)
        saveToConfig()
        return withId
    }

    fun updateScript(id: String, config: ScriptConfig): ScriptConfig? {
        val index = scripts.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = scripts[index]
        val updated = config.copy(
            id = id,
            createdAt = old.createdAt,
            updatedAt = System.currentTimeMillis(),
            lastRunAt = old.lastRunAt,
            lastRunStatus = old.lastRunStatus,
            runCount = old.runCount
        )
        scripts[index] = updated
        saveToConfig()
        return updated
    }

    fun deleteScript(id: String): Boolean {
        val removed = scripts.removeAll { it.id == id }
        if (removed) saveToConfig()
        return removed
    }

    fun deleteScripts(ids: List<String>): Int {
        val idSet = ids.toSet()
        val before = scripts.size
        scripts.removeAll { it.id in idSet }
        val removed = before - scripts.size
        if (removed > 0) saveToConfig()
        return removed
    }

    fun copyScript(id: String): ScriptConfig? {
        val source = getScript(id) ?: return null
        return addScript(
            source.copy(
                id = ScriptConfig.generateId(),
                name = "${source.name} Copy",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                lastRunAt = 0,
                lastRunStatus = "",
                runCount = 0
            )
        )
    }

    fun replaceScripts(newScripts: List<ScriptConfig>) {
        scripts.clear()
        scripts.addAll(newScripts.map { ScriptConfig.ensureId(it) })
        saveToConfig()
    }

    fun importScripts(imported: List<ScriptConfig>, overwrite: Boolean = false): ImportResult {
        var added = 0
        var updated = 0
        val now = System.currentTimeMillis()
        imported.filterNotNull().forEach { raw ->
            val script = ScriptConfig.ensureId(raw)
            val existingIndex = if (script.id.isNotBlank()) scripts.indexOfFirst { it.id == script.id } else -1
            if (existingIndex >= 0) {
                if (overwrite) {
                    val old = scripts[existingIndex]
                    scripts[existingIndex] = script.copy(
                        id = old.id,
                        createdAt = old.createdAt,
                        updatedAt = now,
                        lastRunAt = old.lastRunAt,
                        lastRunStatus = old.lastRunStatus,
                        runCount = old.runCount
                    )
                    updated++
                } else {
                    scripts.add(
                        script.copy(
                            id = ScriptConfig.generateId(),
                            createdAt = if (script.createdAt > 0) script.createdAt else now,
                            updatedAt = now
                        )
                    )
                    added++
                }
            } else {
                scripts.add(
                    script.copy(
                        id = if (script.id.isNotBlank()) script.id else ScriptConfig.generateId(),
                        createdAt = if (script.createdAt > 0) script.createdAt else now,
                        updatedAt = now
                    )
                )
                added++
            }
        }
        if (added > 0 || updated > 0) saveToConfig()
        return ImportResult(added, updated)
    }

    /**
     * 判断待导入的脚本中是否存在与已有脚本 id 相同的冲突。
     */
    fun hasIdConflict(imported: List<ScriptConfig>): Boolean {
        val existingIds = scripts.map { it.id }.toSet()
        return imported.any { it.id.isNotBlank() && it.id in existingIds }
    }

    data class ImportResult(val added: Int, val updated: Int)

    fun renderCommand(script: ScriptConfig, rawParams: Map<String, String>, context: ScriptRunContext = ScriptRunContext.EMPTY): String {
        if (script.command.isBlank()) throw IllegalArgumentException(DeployXBundle.message("script.error.commandEmpty"))
        val normalizedParams = normalizeParams(script, rawParams)
        val variables = collectContextVars(context).toMutableMap()
        normalizedParams.forEach { (key, value) -> variables[key] = shellQuote(value) }

        val placeholder = "__DEPLOYX_ESCAPED_DOLLAR_${UUID.randomUUID()}__"
        val escaped = script.command.replace("$${'$'}{", placeholder)
        val rendered = Regex("\\$\\{([^}]+)}").replace(escaped) { match ->
            val key = match.groupValues[1].trim()
            variables[key] ?: throw IllegalArgumentException(DeployXBundle.message("script.error.unknownVariable", key))
        }.replace(placeholder, "${'$'}{")

        val wrapped = wrapWorkingDir(rendered, script, context)
        return wrapped.trim()
    }

    fun collectContextVars(context: ScriptRunContext): Map<String, String> {
        val vars = linkedMapOf<String, String>()
        context.server?.let { server -> addServerVars(vars, server) }
        context.mapping?.let { mapping -> addMappingVars(vars, mapping) }
        context.remoteDir?.takeIf { it.isNotBlank() }?.let { vars["path.remoteDir"] = shellQuote(it) }
        context.projectBasePath?.takeIf { it.isNotBlank() }?.let { vars["path.projectBase"] = shellQuote(it) }
        context.artifactPath?.takeIf { it.isNotBlank() }?.let { vars["path.artifact"] = shellQuote(it) }
        if (context.localSelectedPaths.isNotEmpty()) {
            vars["path.local"] = shellQuote(context.localSelectedPaths.first())
            vars["path.locals"] = context.localSelectedPaths.joinToString(" ") { shellQuote(it) }
            context.localSelectedPaths.forEachIndexed { index, path -> vars["path.local.$index"] = shellQuote(path) }
        }
        return vars
    }

    fun availableContextKeys(): List<String> = listOf(
        "server.id",
        "server.name",
        "server.host",
        "server.port",
        "server.user",
        "server.address",
        "server.auth",
        "mapping.id",
        "mapping.name",
        "mapping.localDir",
        "mapping.remoteDir",
        "mapping.serverId",
        "path.remoteDir",
        "path.projectBase",
        "path.artifact",
        "path.local",
        "path.locals",
        "path.local.0"
    )

    fun resolveServer(script: ScriptConfig, context: ScriptRunContext, serverId: String? = null): ServerConfig? {
        val resolvedId = serverId?.takeIf { it.isNotBlank() }
            ?: script.serverId.takeIf { it.isNotBlank() }
            ?: context.server?.id
            ?: context.mapping?.serverId
        return resolvedId?.let { ServerManager.getInstance().getServer(it) }
    }

    fun hasDangerousCommand(script: ScriptConfig, command: String): Boolean {
        val lower = command.lowercase()
        val keywords = (script.dangerousKeywords.ifEmpty { ScriptConfig.DEFAULT_DANGEROUS_KEYWORDS })
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
        return keywords.any { lower.contains(it) }
    }

    fun runScript(
        script: ScriptConfig,
        rawParams: Map<String, String>,
        context: ScriptRunContext = ScriptRunContext.EMPTY,
        serverId: String? = null,
        logCallback: ((String) -> Unit)? = null,
        confirmCallback: ((String) -> Boolean)? = null
    ): ScriptRunResult {
        val start = System.currentTimeMillis()
        val normalizedParams = normalizeParams(script, rawParams)
        val server = resolveServer(script, context, serverId)
            ?: throw IllegalArgumentException(DeployXBundle.message("script.error.noServerSelected"))
        val effectiveContext = if (context.server == null) context.copy(server = server) else context
        val command = renderCommand(script, normalizedParams, effectiveContext)

        if (hasDangerousCommand(script, command)) {
            val confirmed = confirmCallback?.invoke(command) ?: !script.confirmBeforeRun
            if (!confirmed) {
                val result = ScriptRunResult(
                    scriptId = script.id,
                    scriptName = script.name,
                    serverId = server.id,
                    resolvedCommand = command,
                    success = false,
                    exitCode = -1,
                    error = DeployXBundle.message("script.userCancelled"),
                    duration = System.currentTimeMillis() - start,
                    params = normalizedParams
                )
                updateRunStats(script.id, false)
                saveHistory(script, server, result)
                return result
            }
        } else if (script.confirmBeforeRun) {
            val confirmed = confirmCallback?.invoke(command) ?: true
            if (!confirmed) {
                val result = ScriptRunResult(
                    scriptId = script.id,
                    scriptName = script.name,
                    serverId = server.id,
                    resolvedCommand = command,
                    success = false,
                    exitCode = -1,
                    error = DeployXBundle.message("script.userCancelled"),
                    duration = System.currentTimeMillis() - start,
                    params = normalizedParams
                )
                updateRunStats(script.id, false)
                saveHistory(script, server, result)
                return result
            }
        }

        logCallback?.invoke(DeployXBundle.message("script.log.executeScript", script.name))
        logCallback?.invoke(DeployXBundle.message("script.log.server", server.displayAddress))
        logCallback?.invoke(DeployXBundle.message("script.log.command", maskSensitive(command, server)))

        val connection = SshConnection(server)
        return try {
            val connectResult = connection.connectWithDetails()
            if (!connectResult.success) {
                val message = connectResult.errorMessage ?: DeployXBundle.message("script.error.cannotConnectServer")
                logCallback?.invoke("[ERROR] $message")
                val result = ScriptRunResult(script.id, script.name, server.id, command, false, -1, error = message, duration = System.currentTimeMillis() - start, params = normalizedParams)
                updateRunStats(script.id, false)
                saveHistory(script, server, result)
                result
            } else {
                val commandResult = connection.executeCommand(command)
                val output = truncateOutput(commandResult.output)
                val error = truncateOutput(commandResult.error)
                if (output.isNotBlank()) logCallback?.invoke(DeployXBundle.message("script.log.output", maskSensitive(output, server)))
                if (error.isNotBlank()) logCallback?.invoke(DeployXBundle.message("script.log.errorOutput", maskSensitive(error, server)))
                logCallback?.invoke(if (commandResult.success) DeployXBundle.message("script.log.executeSuccess") else DeployXBundle.message("script.log.executeFailed", commandResult.exitCode))
                val result = ScriptRunResult(
                    scriptId = script.id,
                    scriptName = script.name,
                    serverId = server.id,
                    resolvedCommand = command,
                    success = commandResult.success,
                    exitCode = commandResult.exitCode,
                    output = output,
                    error = error,
                    duration = System.currentTimeMillis() - start,
                    params = normalizedParams
                )
                updateRunStats(script.id, commandResult.success)
                saveHistory(script, server, result)
                result
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeParams(script: ScriptConfig, rawParams: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        script.params.forEach { param ->
            val raw = rawParams[param.name]?.trim().orEmpty().ifBlank { param.defaultValue.trim() }
            if (param.required && raw.isBlank()) throw IllegalArgumentException(DeployXBundle.message("script.error.paramRequired", param.displayLabel))
            if (raw.isBlank()) {
                result[param.name] = ""
                return@forEach
            }
            result[param.name] = when (param.type) {
                ScriptParam.ParamType.NUMBER -> {
                    raw.toDoubleOrNull() ?: throw IllegalArgumentException(DeployXBundle.message("script.error.paramMustBeNumber", param.displayLabel))
                    raw
                }
                ScriptParam.ParamType.BOOLEAN -> normalizeBoolean(raw, param.displayLabel)
                ScriptParam.ParamType.ENUM -> {
                    if (param.options.isNotEmpty() && raw !in param.options) {
                        throw IllegalArgumentException(DeployXBundle.message("script.error.paramEnumInvalid", param.displayLabel, param.options.joinToString(", ")))
                    }
                    raw
                }
                ScriptParam.ParamType.STRING,
                ScriptParam.ParamType.PATH -> raw
            }
        }
        return result
    }

    private fun normalizeBoolean(value: String, label: String): String {
        return when (value.lowercase()) {
            "true", "yes", "y", "on", "1" -> "true"
            "false", "no", "n", "off", "0" -> "false"
            else -> throw IllegalArgumentException(DeployXBundle.message("script.error.paramMustBeBoolean", label))
        }
    }

    private fun wrapWorkingDir(command: String, script: ScriptConfig, context: ScriptRunContext): String {
        val dir = when {
            script.autoCdRemoteDir && !context.remoteDir.isNullOrBlank() -> context.remoteDir
            script.workingDir.isNotBlank() -> script.workingDir
            else -> null
        } ?: return command
        return "cd ${shellQuote(dir)} && {\n$command\n}"
    }

    private fun addServerVars(vars: MutableMap<String, String>, server: ServerConfig) {
        vars["server.id"] = shellQuote(server.id)
        vars["server.name"] = shellQuote(server.name)
        vars["server.host"] = shellQuote(server.host)
        vars["server.port"] = shellQuote(server.port.toString())
        vars["server.user"] = shellQuote(server.user)
        vars["server.address"] = shellQuote(server.displayAddress)
        vars["server.auth"] = shellQuote(server.authType.value)
    }

    private fun addMappingVars(vars: MutableMap<String, String>, mapping: MappingConfig) {
        vars["mapping.id"] = shellQuote(mapping.effectiveId)
        vars["mapping.name"] = shellQuote(mapping.name)
        vars["mapping.localDir"] = shellQuote(mapping.localDir)
        vars["mapping.remoteDir"] = shellQuote(mapping.remoteDir)
        vars["mapping.serverId"] = shellQuote(mapping.serverId)
    }

    private fun updateRunStats(scriptId: String, success: Boolean) {
        val index = scripts.indexOfFirst { it.id == scriptId }
        if (index >= 0) {
            val script = scripts[index]
            scripts[index] = script.copy(
                lastRunAt = System.currentTimeMillis(),
                lastRunStatus = if (success) "success" else "failed",
                runCount = script.runCount + 1,
                updatedAt = System.currentTimeMillis()
            )
            saveToConfig()
        }
    }

    private fun saveHistory(script: ScriptConfig, server: ServerConfig, result: ScriptRunResult) {
        val safeCommand = maskSensitive(result.resolvedCommand, server)
        val text = buildString {
            appendLine(DeployXBundle.message("script.history.script", script.name))
            appendLine(DeployXBundle.message("script.history.server", server.displayAddress))
            appendLine(DeployXBundle.message("script.history.command"))
            appendLine(safeCommand)
            if (result.output.isNotBlank()) {
                appendLine()
                appendLine(DeployXBundle.message("script.history.output"))
                appendLine(maskSensitive(result.output, server))
            }
            if (result.error.isNotBlank()) {
                appendLine()
                appendLine(DeployXBundle.message("script.history.error"))
                appendLine(maskSensitive(result.error, server))
            }
        }.let { truncateOutput(it, MAX_HISTORY_TEXT_LENGTH) }

        HistoryManager.getInstance().addRecord(
            HistoryRecord(
                type = HistoryRecord.OperationType.SCRIPT,
                sourcePath = script.name,
                serverId = server.id,
                targetPath = script.workingDir.ifBlank { result.serverId },
                fileCount = 0,
                fileSize = 0,
                duration = result.duration,
                status = if (result.success) HistoryRecord.OperationStatus.SUCCESS else HistoryRecord.OperationStatus.FAILED,
                preCommand = safeCommand,
                serverName = server.name,
                serverAddress = server.displayAddress,
                reportText = text
            )
        )
    }

    private fun saveToConfig() {
        ConfigManager.getInstance().saveScripts(scripts)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun maskSensitive(text: String, server: ServerConfig): String {
        var result = text
        if (server.password.isNotBlank()) result = result.replace(server.password, "******")
        return result
    }

    private fun truncateOutput(text: String, maxLength: Int = 100_000): String {
        if (text.length <= maxLength) return text
        val tail = text.takeLast(maxLength)
        return "...[output truncated, kept last $maxLength chars]...\n$tail"
    }
}
