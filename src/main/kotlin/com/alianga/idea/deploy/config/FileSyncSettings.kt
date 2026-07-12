package com.alianga.idea.deploy.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 插件持久化设置 - 存储在 IDE 配置中
 */
@Service
@State(
    name = "com.alianga.idea.deploy.FileSyncSettings",
    storages = [Storage("FileSyncTool.xml")]
)
class FileSyncSettings : PersistentStateComponent<FileSyncSettings.State> {

    data class State(
        var defaultServerId: String = "",
        var compress: Boolean = true,
        var showProgress: Boolean = true,
        var autoBackup: Boolean = true,
        var maxBackups: Int = 10,
        var backupSuffix: String = ".bak",
        var connectTimeout: Int = 10000,
        var autoRefresh: Boolean = true,
        // rsync 配置
        var rsyncPath: String = "rsync",
        var rsyncOptions: String = "-avz --progress --stats",
        var sshpassAvailable: Boolean = true,
        var transferMode: String = "AUTO",
        // language settings: "system" | "en" | "zh_CN"
        var language: String = "system",
        // 首次传输时检测到 Windows 未安装 rsync，用户选择"否"后置为 true，不再提示
        var declinedRsyncAutoInstall: Boolean = false,
        // 部署/上传完成后是否弹出系统通知
        var systemNotification: Boolean = true,
        // 日志区字体大小（pt），默认 12
        var logFontSize: Int = 12,
        // 上次导出配置的目录（用于 JFileChooser 记住位置）
        var lastExportDir: String = "",
        // 上次导入配置的目录（用于 JFileChooser 记住位置）
        var lastImportDir: String = "",
        // 远程文件浏览器：每个服务器上次打开的远程目录（serverId -> path）
        var browserLastPaths: MutableMap<String, String> = java.util.LinkedHashMap(),
        // 远程文件浏览器：每个服务器手动输入并成功进入过的路径历史（serverId -> [paths]）
        var browserPathHistory: MutableMap<String, MutableList<String>> = java.util.LinkedHashMap()
    )

    private var myState = State()

    companion object {
        fun getInstance(): FileSyncSettings =
            ApplicationManager.getApplication().getService(FileSyncSettings::class.java)
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    var defaultServerId: String
        get() = myState.defaultServerId
        set(value) { myState.defaultServerId = value }

    var compress: Boolean
        get() = myState.compress
        set(value) { myState.compress = value }

    var showProgress: Boolean
        get() = myState.showProgress
        set(value) { myState.showProgress = value }

    var autoBackup: Boolean
        get() = myState.autoBackup
        set(value) { myState.autoBackup = value }

    var maxBackups: Int
        get() = myState.maxBackups
        set(value) { myState.maxBackups = value }

    var backupSuffix: String
        get() = myState.backupSuffix
        set(value) { myState.backupSuffix = value }

    var connectTimeout: Int
        get() = myState.connectTimeout
        set(value) { myState.connectTimeout = value }

    var autoRefresh: Boolean
        get() = myState.autoRefresh
        set(value) { myState.autoRefresh = value }

    var rsyncPath: String
        get() = myState.rsyncPath
        set(value) { myState.rsyncPath = value }

    var rsyncOptions: String
        get() = myState.rsyncOptions
        set(value) { myState.rsyncOptions = value }

    var sshpassAvailable: Boolean
        get() = myState.sshpassAvailable
        set(value) { myState.sshpassAvailable = value }

    var transferMode: String
        get() = myState.transferMode
        set(value) { myState.transferMode = value }

    var language: String
        get() = myState.language
        set(value) { myState.language = value }

    var declinedRsyncAutoInstall: Boolean
        get() = myState.declinedRsyncAutoInstall
        set(value) { myState.declinedRsyncAutoInstall = value }

    var systemNotification: Boolean
        get() = myState.systemNotification
        set(value) { myState.systemNotification = value }

    var logFontSize: Int
        get() = myState.logFontSize.coerceIn(8, 36)
        set(value) { myState.logFontSize = value }

    var lastExportDir: String
        get() = myState.lastExportDir
        set(value) { myState.lastExportDir = value }

    var lastImportDir: String
        get() = myState.lastImportDir
        set(value) { myState.lastImportDir = value }

    // ===== 远程文件浏览器持久化 =====

    /** 获取服务器上次打开的远程目录，不存在返回 null */
    fun getBrowserLastPath(serverId: String): String? = myState.browserLastPaths[serverId]

    /** 记录服务器上次打开的远程目录 */
    fun setBrowserLastPath(serverId: String, path: String) {
        myState.browserLastPaths[serverId] = path
    }

    /** 获取服务器的路径历史（成功进入过的路径列表），返回去重副本 */
    fun getBrowserPathHistory(serverId: String): List<String> =
        myState.browserPathHistory[serverId]?.distinct()?.toList() ?: emptyList()

    /** 追加一条路径历史（移除全部旧条目后放到最前面，最多保留 20 条） */
    fun addBrowserPathHistory(serverId: String, path: String) {
        val list = myState.browserPathHistory.getOrPut(serverId) { java.util.ArrayList() }
        // 移除全部匹配的旧条目（防止 XML 序列化导致重复残留）
        list.removeAll { it == path }
        list.add(0, path)
        while (list.size > 20) list.removeAt(list.size - 1)
    }

    /** 删除服务器的单条路径历史 */
    fun removeBrowserPathHistory(serverId: String, path: String) {
        myState.browserPathHistory[serverId]?.remove(path)
    }

    /** 清空服务器的全部路径历史 */
    fun clearBrowserPathHistory(serverId: String) {
        myState.browserPathHistory[serverId]?.clear()
    }
}
