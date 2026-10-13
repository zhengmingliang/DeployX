package com.alianga.idea.deploy.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 初始化顺序守护（1.1.1 修复 hdRollbackBtn NPE）：
 * 在类的 init {} 里会调用 setupUI 等方法，若带初始化器的属性声明在 init 之后，执行时仍为 null。
 * 平台测试夹具在本工程（本地 IDE 2026.2 + gradle-intellij-plugin 1.x）下无法启动，
 * 这里对 UI 面板源码做静态检查：顶层类中，第一个 init 块之后不允许再出现带初始化器的属性。
 *
 * @author 郑明亮
 */
class InitOrderTest {
    private val root = File("src/main/kotlin")

    private val propAfterInit = Regex("""^    (private |protected |internal |public |override )*(val|var) \w+(\s*:[^=]+)?\s*(=|by )""")

    @Test fun noInitializedPropertyAfterInitBlock() {
        val problems = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            var initLine = -1
            f.readLines().forEachIndexed { i, line ->
                if (line.matches(Regex("^(private |internal )?(data )?(abstract |open )?class .*"))) initLine = -1
                if (line.startsWith("    init {")) { if (initLine < 0) initLine = i }
                else if (initLine >= 0 && propAfterInit.containsMatchIn(line) && !line.contains("by lazy")) {
                    problems += "${f.path}:${i + 1}: $line"
                }
            }
        }
        assertTrue(problems.isEmpty(), "属性声明在 init 之后：\n" + problems.joinToString("\n"))
    }
}
