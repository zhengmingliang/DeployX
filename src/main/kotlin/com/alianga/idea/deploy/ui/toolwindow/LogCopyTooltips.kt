package com.alianga.idea.deploy.ui.toolwindow

/** #26：日志子页签复制按钮的 tooltip key；「全部」页签（serverId 为空）不提「此服务器」。 */
internal fun copyTabTooltipKey(serverId: String?): String =
    if (serverId.isNullOrBlank()) "toolwindow.action.copyLog.tooltip.allTab" else "toolwindow.action.copyLog.tooltip"
