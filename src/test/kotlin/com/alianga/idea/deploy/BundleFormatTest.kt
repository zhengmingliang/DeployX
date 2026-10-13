package com.alianga.idea.deploy

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.text.MessageFormat
import java.util.Properties

/**
 * 所有 bundle 文案（en / zh）都能被 MessageFormat 格式化而不抛异常；
 * 不带占位符的文案不得含花括号（以后加参数时会炸）；带占位符的文案里单引号必须成对（''）或用于转义花括号。
 *
 * @author 郑明亮
 */
class BundleFormatTest {
    private val files = listOf("DeployXBundle.properties", "DeployXBundle_zh_CN.properties")
        .map { File("src/main/resources/messages/$it") }

    @Test fun everyKeyFormats() {
        val problems = mutableListOf<String>()
        val args = Array<Any>(10) { "ARG$it" }
        files.forEach { f ->
            val p = Properties().apply { f.reader(Charsets.UTF_8).use { load(it) } }
            p.stringPropertyNames().sorted().forEach { k ->
                val v = p.getProperty(k)
                val hasArgs = Regex("""\{\d+(,[^}]*)?}""").containsMatchIn(v)
                try { MessageFormat.format(v, *args) } catch (e: Exception) { problems += "${f.name} $k: ${e.message}" }
                if (!hasArgs && (v.contains('{') || v.contains('}'))) problems += "${f.name} $k: 无占位符却含花括号"
                if (hasArgs) {
                    val stripped = v.replace("''", "").replace("'{'", "").replace("'}'", "")
                    if (stripped.contains('\'')) problems += "${f.name} $k: 单引号未转义"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
