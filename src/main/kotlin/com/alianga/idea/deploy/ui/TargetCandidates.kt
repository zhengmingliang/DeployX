package com.alianga.idea.deploy.ui

/**
 * 部署目标对话框的候选服务器计算（实施方案 3.8「候选服务器的过滤规则」，纯函数）。
 *
 * 路径 → 映射的匹配（按路径段比较，`lib` 不会误匹配 `lib2`）由 MappingManager 完成；
 * 这里只负责多选时取交集：每个选中项都有映射的服务器才进入候选，否则一次操作里会有路径无处可传。
 *
 * @author 郑明亮
 */
object TargetCandidates {

    /**
     * @param serverIdsPerFile 每个（已匹配到映射的）选中项各自命中的服务器 id，按出现顺序
     * @return 覆盖全部选中项的服务器 id，保持首次出现顺序；入参为空返回空
     */
    fun coveringAll(serverIdsPerFile: List<Collection<String>>): List<String> {
        if (serverIdsPerFile.isEmpty()) return emptyList()
        val ordered = LinkedHashSet<String>()
        serverIdsPerFile.forEach { ordered.addAll(it) }
        return ordered.filter { id -> serverIdsPerFile.all { id in it } }
    }
}
