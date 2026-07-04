package com.alianga.idea.deploy.action

import com.alianga.idea.deploy.DeployXBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * DeployX 右键菜单分组（popup 子菜单）。
 *
 * 文案不通过 plugin.xml 的 `%key` 声明（平台对 `%key` 的静态解析基于 JVM
 * 默认 Locale，无法响应运行时语言切换），而是在 [update] 中通过
 * [DeployXBundle] 动态设置，使菜单每次弹出时按当前语言刷新。
 */
class DeployXMenuGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = DeployXBundle.message("action.menu.text")
        e.presentation.description = DeployXBundle.message("action.menu.description")
    }
}
