package com.alianga.idea.deploy.ui

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.ServerConfig
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import javax.swing.JList

/**
 * 服务器下拉渲染：状态点 + 「id · 名称」+ 灰色 user@host:port。
 *
 * 状态点用 [ColorIcon]（规范要求不用图片）；插件不常驻探测连通性，统一显示为中性色，
 * 本地服务器另附「本地」标签。
 *
 * @author 郑明亮
 */
class ServerComboRenderer(private val serverAt: (Int, Any?) -> ServerConfig?) : ColoredListCellRenderer<Any>() {
    override fun customizeCellRenderer(list: JList<out Any>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean) {
        val server = serverAt(index, value) ?: run { append(value?.toString() ?: ""); return }
        icon = ColorIcon(JBUI.scale(8), DeployXColors.unknown)
        iconTextGap = JBUI.scale(6)
        append(ServerDisplay.title(server.id, server.name), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (server.isLocal) {
            append("  " + DeployXBundle.message("dialog.server.type.local"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else {
            append("  " + ServerDisplay.address(server.user, server.host, server.port), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
