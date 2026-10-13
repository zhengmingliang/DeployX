package com.alianga.idea.deploy.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * DeployX 颜色令牌（对应《设计规范 · 令牌与组件》语义令牌表）。
 *
 * 全部走 [JBColor] / [JBColor.namedColor]：主题键存在时取主题值，缺失时落到 (亮, 暗) 兜底；
 * 调用方不得缓存解析后的 [Color]，这样运行中切换主题会自动重绘、不留色差。
 *
 * @author 郑明亮
 */
object DeployXColors {
    /** 成功：亮 #208A3C / 暗 #5FB865 */
    val success: Color get() = JBColor.namedColor("Label.successForeground", JBColor(0x208A3C, 0x5FB865))
    /** 错误：亮 #C7222D / 暗 #F75464 */
    val error: Color get() = JBColor.namedColor("Label.errorForeground", JBColor(0xC7222D, 0xF75464))
    /** 警告：平台无官方键，自定义兜底 */
    val warning: Color get() = JBColor(0x9E6A03, 0xE5B93D)
    /** 次要文字 / 注释 */
    val secondary: Color get() = UIUtil.getContextHelpForeground()
    /** 链接 */
    val link: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED
    /** 强调色（主按钮 / 焦点环） */
    val accent: Color get() = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x3574F0))
    /** 分隔线 / 边框 */
    val border: Color get() = JBColor.border()
    /** 状态未知（中性灰） */
    val unknown: Color get() = JBColor(0x6C707E, 0x9DA0A8)
}
