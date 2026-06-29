package com.alianga.idea.filesync.model

import com.google.gson.annotations.SerializedName

/**
 * 目录映射配置
 */
data class MappingConfig(
    /** 唯一标识（自动生成，用于 CRUD 操作，不显示在界面） */
    @SerializedName("id")
    val id: String = "",

    /** 映射名称（允许重复，用于显示） */
    @SerializedName("name")
    val name: String,

    @SerializedName("local_dir")
    val localDir: String,

    @SerializedName("server_id")
    val serverId: String,

    @SerializedName("remote_dir")
    val remoteDir: String,

    /** 是否启用部署前备份 */
    @SerializedName("backup_enabled")
    val backupEnabled: Boolean = false,

    @SerializedName("backup_dir")
    val backupDir: String = "",

    /** 备份源路径（可选，指定需要备份的远程文件/目录，而非自动推断） */
    @SerializedName("backup_source")
    val backupSource: String = "",

    /** 是否启用上传后解压 */
    @SerializedName("unzip_enabled")
    val unzipEnabled: Boolean = false,

    @SerializedName("unzip_dest")
    val unzipDest: String = "",

    @SerializedName("exclude")
    val exclude: List<String> = emptyList(),

    /** 是否启用上传前命令，null 表示旧配置：按命令是否为空决定 */
    @SerializedName("pre_command_enabled")
    val preCommandEnabled: Boolean? = null,

    /** 上传前在远程服务器执行的命令 */
    @SerializedName("pre_command")
    val preCommand: String = "",

    /** 是否启用上传后命令，null 表示旧配置：按命令是否为空决定 */
    @SerializedName("post_command_enabled")
    val postCommandEnabled: Boolean? = null,

    /** 上传后在远程服务器执行的命令 */
    @SerializedName("post_command")
    val postCommand: String = ""
) {
    companion object {
        fun generateId(): String =
            System.currentTimeMillis().toString(36) + "_" + (Math.random() * 100000).toInt().toString(36)

        /**
         * 确保配置有唯一ID（兼容旧数据：旧数据没有 id 字段，用 name 的 hash 作为 id）
         */
        fun ensureId(config: MappingConfig): MappingConfig {
            return if (config.id.isBlank()) {
                config.copy(id = "legacy_${config.name.hashCode().toString(16)}")
            } else {
                config
            }
        }
    }

    /** 获取有效ID（兼容旧数据） */
    val effectiveId: String
        get() = if (id.isBlank()) "legacy_${name.hashCode().toString(16)}" else id

    /** 兼容旧配置：旧配置没有启用字段时，命令非空即视为启用 */
    val effectivePreCommandEnabled: Boolean
        get() = preCommandEnabled ?: preCommand.isNotBlank()

    /** 兼容旧配置：旧配置没有启用字段时，命令非空即视为启用 */
    val effectivePostCommandEnabled: Boolean
        get() = postCommandEnabled ?: postCommand.isNotBlank()
}
