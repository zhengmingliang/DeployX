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
        var transferMode: String = "AUTO"
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
}
