package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.service.MappingManager
import java.awt.Component
import java.awt.FontMetrics
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.SwingUtilities

/**
 * 目录映射候选列表的单元格渲染器（1.0.8）
 *
 * 修复嵌套映射场景下条目文本过长的展示缺陷：
 * - 条目超宽时对本地/远程目录做**中间省略**（保留首尾关键片段），保证映射名与目录边界
 *   始终可见——既不出现右侧被裁切，也不会撑出横向滚动条
 * - 悬浮 tooltip 展示完整条目文本，便于查看全路径
 * - 列表第一项（排序后最深层级 = 系统自动匹配项）追加「默认」标记
 *
 * 约定：列表模型必须是**按优先级排序后**的候选（第一项即默认项），由调用方排序后放入。
 *
 * 尺寸由字体度量实时计算，随 IDE 字体设置与界面语言自适应；列表尚未布局时按外层
 * 滚动面板的首选宽度兜底，保证首次绘制即不裁切。
 */
class MappingListCellRenderer : DefaultListCellRenderer() {

    override fun getListCellRendererComponent(
        list: JList<*>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): java.awt.Component {
        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        val resolved = value as? MappingManager.ResolvedMapping ?: return component
        if (component is JLabel) {
            val isDefault = index == 0
            component.toolTipText = MappingManager.formatMappingEntry(resolved, isDefault)
            component.text = buildDisplayText(resolved, isDefault, list, component)
        }
        return component
    }

    private fun buildDisplayText(
        resolved: MappingManager.ResolvedMapping,
        isDefault: Boolean,
        list: JList<*>,
        label: JLabel
    ): String {
        val name = resolved.mapping.name.ifBlank { resolved.mapping.localDir }
        val marker = if (isDefault) " " + DeployXBundle.message("dialog.mapping.select.entry.default") else ""
        val fontMetrics = label.getFontMetrics(label.font)
        // 可用宽度扣除「（ → ）」骨架、默认标记与单元格内边距
        val availablePx =
            availableWidthPx(list) - fontMetrics.stringWidth("（ → ）$marker") - CELL_H_PADDING_PX
        if (availablePx <= 0) return name + marker // 空间极小：只显示映射名
        val perPathPx = availablePx / 2
        val local = shortenToWidth(resolved.mapping.localDir, perPathPx, fontMetrics)
        val remote = shortenToWidth(resolved.resolvedRemoteDir, perPathPx, fontMetrics)
        return "$name（$local → $remote）$marker"
    }

    /**
     * 中间省略：保留首尾片段，整体宽度不超过 [maxPx]。
     * 二分查找可保留的字符数，精度到字体度量。
     */
    private fun shortenToWidth(text: String, maxPx: Int, fontMetrics: FontMetrics): String {
        if (fontMetrics.stringWidth(text) <= maxPx) return text
        if (fontMetrics.stringWidth(ELLIPSIS) > maxPx) return ""
        var low = 2
        var high = text.length - 1
        var best = ELLIPSIS
        while (low <= high) {
            val mid = (low + high) / 2
            val head = mid / 2
            val candidate = text.take(head) + ELLIPSIS + text.takeLast(mid - head)
            if (fontMetrics.stringWidth(candidate) <= maxPx) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /** 列表可视宽度（px）：优先取 viewport 实际宽度，未布局时按滚动面板首选宽度兜底 */
    private fun availableWidthPx(list: JList<*>): Int {
        val viewport = SwingUtilities.getUnwrappedParent(list) as? JViewport
        if (viewport != null && viewport.width > 0) return viewport.width
        val preferred = (viewport?.parent as? JScrollPane)?.preferredSize?.width ?: 0
        return if (preferred > 0) preferred else DEFAULT_AVAILABLE_PX
    }

    private companion object {
        /** 省略号 */
        const val ELLIPSIS = "…"

        /** 单元格水平内边距 + 安全余量（px） */
        const val CELL_H_PADDING_PX = 24

        /** 列表未布局时的兜底可用宽度（px） */
        const val DEFAULT_AVAILABLE_PX = 500
    }
}

/** 映射列表行高（px，缩放前），供各对话框动态计算列表高度 */
internal const val MAPPING_ROW_HEIGHT: Int = 28

/** 映射列表可见行数下限：至少 2 行，保证父子两条映射同时可见 */
internal const val MAPPING_MIN_ROWS: Int = 2

/** 映射列表可见行数上限：超出滚动，避免弹窗过高 */
internal const val MAPPING_MAX_ROWS: Int = 5
