package com.alianga.idea.deploy.ui

/**
 * 底部拆分按钮的模式（实施方案 3.5）。主体按钮记住上次选择的模式，
 * 与 IDE「运行」按钮一致；持久化值为 [name]，未知值回落到默认。
 *
 * @author 郑明亮
 */
enum class DeployMode(val push: Boolean) {
    DEPLOY(true), QUICK_PUSH(true), PREVIEW_PUSH(true),
    PULL(false), PREVIEW_PULL(false);

    companion object {
        fun parse(value: String?, default: DeployMode): DeployMode =
            entries.firstOrNull { it.name == value && it.push == default.push } ?: default
    }
}
