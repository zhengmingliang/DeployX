package com.alianga.idea.deploy.ui

/**
 * 操作页底部「空闲态摘要」与缺项校验（纯函数，便于单测）。
 *
 * 对应实施方案 3.5 / 3.6：空闲时显示「部署 X → 服务器 远程路径」+「流程：…」两行，
 * 取代原先无信息的灰色进度条 +「就绪」；路径或服务器缺失时写明缺什么并置灰操作按钮。
 *
 * @author 郑明亮
 */
object OperationSummary {

    enum class Missing { SERVER, LOCAL, REMOTE }

    data class Result(val headline: String, val flow: String, val missing: List<Missing>) {
        val ready: Boolean get() = missing.isEmpty()
    }

    /**
     * @param labels 本地化文案提供者：key ∈ deploy / flow / backup / upload / unzip / pre / post / arrow
     */
    fun build(
        localPath: String,
        serverTitle: String?,
        remotePath: String,
        backup: Boolean,
        unzip: Boolean,
        preCommand: String,
        postCommand: String,
        labels: (String) -> String
    ): Result {
        val missing = buildList {
            if (serverTitle.isNullOrBlank()) add(Missing.SERVER)
            if (localPath.isBlank()) add(Missing.LOCAL)
            if (remotePath.isBlank()) add(Missing.REMOTE)
        }
        val name = shortName(localPath)
        val headline = "${labels("deploy")} $name → ${serverTitle.orEmpty()} ${remotePath.trim()}".replace(Regex(" +"), " ").trim()
        return Result(headline, flowText(backup, unzip, preCommand, postCommand, labels), missing)
    }

    /** 「流程：备份 → 上传 → 解压 → 后置命令」，未启用的步骤省略 */
    fun flowText(backup: Boolean, unzip: Boolean, pre: String, post: String, labels: (String) -> String): String {
        val steps = buildList {
            if (pre.isNotBlank()) add(labels("pre"))
            if (backup) add(labels("backup"))
            add(labels("upload"))
            if (unzip) add(labels("unzip"))
            if (post.isNotBlank()) add(labels("post"))
        }
        return labels("flow") + steps.joinToString(" → ")
    }

    /** 路径末段（目录去掉尾部分隔符），空路径返回空串 */
    fun shortName(path: String): String {
        val p = path.trim().trimEnd('/', '\\')
        if (p.isEmpty()) return ""
        return p.substringAfterLast('/').substringAfterLast('\\')
    }
}
