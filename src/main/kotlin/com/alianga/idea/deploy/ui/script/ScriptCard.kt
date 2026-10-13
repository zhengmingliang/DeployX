package com.alianga.idea.deploy.ui.script

import com.alianga.idea.deploy.model.ScriptConfig

/**
 * 脚本卡片 / 详情的纯逻辑（便于单测）。
 *
 * @author 郑明亮
 */
object ScriptCard {

    /** 命令行数（忽略空行） */
    fun commandLines(command: String): Int = command.lines().count { it.isNotBlank() }

    /** 运行按钮：绑定了服务器时直接运行（「运行」），否则「运行…」弹对话框选服务器 */
    fun runsDirectly(script: ScriptConfig): Boolean = script.serverId.isNotBlank()

    /** 复制为新脚本：名称加后缀、统计清零 */
    fun duplicate(source: ScriptConfig, newId: String, suffix: String, now: Long): ScriptConfig = source.copy(
        id = newId, name = source.name + suffix, createdAt = now, updatedAt = now,
        lastRunAt = 0, lastRunStatus = "", runCount = 0
    )

    /** 有参数时才显示「预览命令 / 显示模板」切换 */
    fun hasParams(script: ScriptConfig): Boolean = script.params.isNotEmpty()

    /** 行号文本（编辑区行号槽） */
    fun lineNumbers(text: String): String = (1..text.split('\n').size).joinToString("\n")
}
