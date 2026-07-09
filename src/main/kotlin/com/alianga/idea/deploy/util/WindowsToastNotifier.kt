package com.alianga.idea.deploy.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.system.OS

/**
 * Windows 10/11 原生 Toast 通知（通知中心 / Action Center）。
 *
 * 背景：IntelliJ Platform 的 [com.intellij.ui.SystemNotifications] 在 Windows 上走
 * [java.awt.SystemTray]/[java.awt.TrayIcon.displayMessage]，这是已废弃的经典托盘气泡，
 * 在 Windows 10/11 上极不可靠：
 * - `SystemTray.isSupported()` 在部分 Windows 配置下返回 false
 * - `new TrayIcon(...)` / `SystemTray.add(...)` 抛 AWTException 时被平台静默吞掉（getWin10Instance 返回 null）
 * - 即便成功，Windows 11 默认隐藏新托盘图标，气泡被"专注助手"/通知设置屏蔽
 *
 * 本工具改用 PowerShell 调用 WinRT `Windows.UI.Notifications` API，弹出真正的 Toast，
 * 稳定显示在 Windows 10/11 通知中心。
 *
 * 实现要点：
 * - PowerShell 在 Win10/11 均自带，无需额外依赖
 * - 命令经 Base64(UTF-16LE) 编码后用 -EncodedCommand 传入，规避所有引号/转义/注入问题
 * - ExecUtil.execAndReadLine 标注 @RequiresBackgroundThread，故整体在 pooled 线程异步执行，
 *   调用方无需关心线程（与 Linux LibNotifyWrapper 的 executeOnPooledThread 模式一致）
 * - 失败时静默（仅记日志），不抛异常，由调用方决定是否回退到 IDE Balloon
 */
object WindowsToastNotifier {

    private val LOG = Logger.getInstance(WindowsToastNotifier::class.java)

    /**
     * PowerShell 的 AppUserModelID（系统内置 shell 应用标识路径）。
     * Toast 通知要求调用方有已注册的 AUMID；用 PowerShell 自身的 AUMID 可保证稳定弹出，
     * 通知中心里会显示为 "Windows PowerShell"。如需归属 IDE，可改为 IDE 的 AUMID。
     */
    private const val APP_ID = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe"

    /** 是否可用：仅 Windows 10+。更早版本无 WinRT Toast API。 */
    val isAvailable: Boolean
        get() = OS.CURRENT == OS.Windows && OS.CURRENT.isAtLeast(10, 0)

    /**
     * 异步弹出一条 Windows Toast 通知（fire-and-forget）。
     *
     * @param title 标题
     * @param message 正文（会自动转义 XML 特殊字符）
     */
    fun notify(title: String, message: String) {
        if (!isAvailable) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val script = buildScript(title, message)
                // Base64(UTF-16LE) 编码命令，规避引号/转义/注入
                val encoded = java.util.Base64.getEncoder()
                    .encodeToString(script.toByteArray(charset("UTF-16LE")))
                val cmd = GeneralCommandLine("powershell.exe")
                    .withParameters(
                        "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", encoded
                    )
                val output = ExecUtil.execAndReadLine(cmd)
                if (output == null) {
                    LOG.debug("Windows toast: powershell produced no output")
                } else if (output != "OK") {
                    // PowerShell 脚本捕获异常时输出异常消息
                    LOG.warn("Windows toast failed: $output")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to show Windows toast notification", e)
            }
        }
    }

    /**
     * 构造 PowerShell 脚本：加载 WinRT 类型，构建 ToastText02（标题 + 正文）并提交。
     */
    private fun buildScript(title: String, message: String): String {
        val xmlTitle = escapeXml(title.ifBlank { "DeployX" })
        val xmlMessage = escapeXml(message)
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                [void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
                [void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime]
                ${'$'}template = @"
                <toast>
                    <visual>
                        <binding template="ToastText02">
                            <text id="1">$xmlTitle</text>
                            <text id="2">$xmlMessage</text>
                        </binding>
                    </visual>
                </toast>
"@
                ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
                ${'$'}xml.LoadXml(${'$'}template)
                ${'$'}toast = New-Object Windows.UI.Notifications.ToastNotification ${'$'}xml
                ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$APP_ID')
                ${'$'}notifier.Show(${'$'}toast)
                Write-Output 'OK'
            } catch {
                Write-Output ${'$'}_.Exception.Message
            }
        """.trimIndent()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("\n", "&#10;")
}
