package com.alianga.idea.deploy.ui.dialog

import com.alianga.idea.deploy.DeployXBundle
import com.alianga.idea.deploy.model.MappingConfig
import com.alianga.idea.deploy.service.MappingManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * 映射选择对话框（1.0.8 新增）
 *
 * 用于同一服务器配置了多条嵌套（父子目录）路径映射的场景：系统默认自动匹配
 * 最深层级（子目录）的映射，本对话框允许用户手动改选次优先级（父目录）的映射，
 * 以支持不同场景下的灵活部署需求。
 *
 * 典型场景：
 * - 本地 `.../collection-server/target/lib` -> 服务端 `.../WEB-INF/lib/`（子目录映射）
 * - 本地 `.../collection-server/target`      -> 服务端 `/app/cdb/collection`（父目录映射）
 *
 * 候选映射按优先级排序（`localDir` 长度倒序，最深层级在前），默认选中第一项
 * （即自动匹配结果，与不弹本对话框时的行为保持一致），用户可改选其他映射。
 *
 * 适用入口：
 * - 预览同步（PreviewSyncAction，无服务器选择对话框可复用）
 * - 工具窗口操作面板的「快速推送」「拉取」（手动输入路径，无服务器选择对话框）
 */
class MappingSelectionDialog(
    private val candidates: List<MappingManager.ResolvedMapping>,
    private val serverDisplayName: String,
    private val titleText: String = DeployXBundle.message("dialog.mapping.select.title"),
    private val messageText: String = DeployXBundle.message("dialog.mapping.select.message", serverDisplayName)
) : DialogWrapper(null) {

    /** 按优先级排序后的候选映射（最深层级在前，即默认项） */
    private val sortedCandidates: List<MappingManager.ResolvedMapping> =
        MappingManager.sortByPriority(candidates)

    private val listModel = CollectionListModel(sortedCandidates)

    private val mappingList = JBList(listModel).apply {
        // 1.0.8：长路径条目中间省略 + tooltip 展示全文，避免文本被裁切
        cellRenderer = MappingListCellRenderer()
    }

    /** 用户选中的映射（对话框取消时为 null） */
    var selectedMapping: MappingConfig? = null
        private set

    init {
        title = titleText
        mappingList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        if (sortedCandidates.isNotEmpty()) {
            // 默认选中自动匹配的最深层级映射，保持与原有行为一致
            mappingList.selectedIndex = 0
        }
        // 双击列表项直接确认选择（与 ServerSelectionDialog 行为一致）
        mappingList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2 && sortedCandidates.isNotEmpty()) {
                    doOKAction()
                }
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent {
        // 1.0.8：按候选行数动态设置列表高度（2~5 行），保证候选完整展示
        val rows = sortedCandidates.size.coerceIn(MAPPING_MIN_ROWS, MAPPING_MAX_ROWS)
        val scrollPane = JBScrollPane(mappingList).apply {
            preferredSize = JBUI.size(
                JBUI.scale(560),
                JBUI.scale(MAPPING_ROW_HEIGHT) * rows + JBUI.scale(8)
            )
        }

        val messageLabel = JBLabel(messageText).apply {
            // 文案较长，启用自动换行，避免把对话框撑宽
            setAllowAutoWrapping(true)
        }

        val panel = FormBuilder.createFormBuilder()
            .addComponent(messageLabel)
            .addVerticalGap(JBUI.scale(6))
            .addComponent(JBLabel(DeployXBundle.message("dialog.mapping.select.label.mappings")))
            .addVerticalGap(JBUI.scale(4))
            .addComponent(scrollPane)
            .panel

        panel.preferredSize = JBUI.size(
            JBUI.scale(600),
            JBUI.scale(110) + JBUI.scale(MAPPING_ROW_HEIGHT) * rows
        )
        return panel
    }

    override fun doOKAction() {
        val index = mappingList.selectedIndex
        selectedMapping = sortedCandidates.getOrNull(index)?.mapping
        super.doOKAction()
    }
}
