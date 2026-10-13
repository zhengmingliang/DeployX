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
        labels: (String) -> String,
        mode: DeployMode = DeployMode.DEPLOY
    ): Result {
        val missing = buildList {
            if (serverTitle.isNullOrBlank()) add(Missing.SERVER)
            if (localPath.isBlank()) add(Missing.LOCAL)
            if (remotePath.isBlank()) add(Missing.REMOTE)
        }
        val name = shortName(localPath)
        val headline = "${labels("deploy")} $name → ${serverTitle.orEmpty()} ${remotePath.trim()}".replace(Regex(" +"), " ").trim()
        return Result(headline, flowText(backup, unzip, preCommand, postCommand, labels, mode), missing)
    }

    /**
     * 「流程：备份 → 前置命令 → 上传 → 解压 → 后置命令」（原型顺序），未启用的步骤省略；
     * 快速推送只写「上传」，预览写「预览（dry-run）」，拉取写「拉取」。
     */
    fun flowText(backup: Boolean, unzip: Boolean, pre: String, post: String, labels: (String) -> String,
                 mode: DeployMode = DeployMode.DEPLOY): String {
        when (mode) {
            DeployMode.QUICK_PUSH -> return labels("flow") + labels("upload")
            DeployMode.PREVIEW_PUSH, DeployMode.PREVIEW_PULL -> return labels("flow") + labels("preview")
            DeployMode.PULL -> return labels("flow") + labels("pull")
            DeployMode.DEPLOY -> {}
        }
        val steps = buildList {
            if (backup) add(labels("backup"))
            if (pre.isNotBlank()) add(labels("pre"))
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
