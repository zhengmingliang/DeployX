import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.alianga.idea.deploy"
version = "1.1.0"

// 本地 IDEA 安装路径（与 localPath 保持一致）
val ideaHome = "/home/zml/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate"

// CloudTerminalRunner/CloudTerminalProcess 位于 terminal 插件的独立 module jar 中，
// plugins.set 不会自动加入，这里手动补充（compileOnly：运行时由 IDE 的 terminal 插件提供）
// 使用 file tree 兼容不同版本的 jar 位置
val terminalCloudJar = fileTree(ideaHome) {
    include("plugins/terminal/lib/**/intellij.terminal.cloud.jar")
    include("plugins/terminal/lib/intellij.terminal.cloud.jar")
}.singleOrNull() ?: File("$ideaHome/plugins/terminal/lib/modules/intellij.terminal.cloud.jar")

repositories {
    mavenCentral()
}

dependencies {
    // SSH connection
    implementation("com.github.mwiede:jsch:2.28.3")

    // JSON processing
    implementation("com.google.code.gson:gson:2.10.1")

    // CloudTerminalRunner/CloudTerminalProcess 等类（运行时由 IDE terminal 插件提供）
    compileOnly(files(terminalCloudJar))

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.google.code.gson:gson:2.10.1")
}

tasks.test {
    useJUnitPlatform()
    // 单测只覆盖纯逻辑，不需要 IDE 运行时；gradle-intellij-plugin 1.x 解析本地 IDE 的
    // vmoptions（$IDE_HOME 变量）会抛 IndexOutOfBounds，导致 test 任务无法执行，这里移除其 JVM 参数注入。
    doFirst {
        jvmArgumentProviders.clear()
        systemProperties.remove("java.system.class.loader")
    }
}

intellij {
    // 使用本地已安装的 IntelliJ IDEA Ultimate（避免下载 ~700MB SDK）
    localPath.set(ideaHome)
    // 依赖 terminal 插件，使其核心类在编译时可用（TerminalView/AbstractTerminalRunner 等）
    plugins.set(listOf("org.jetbrains.plugins.terminal"))
    pluginName.set("DeployX")
    updateSinceUntilBuild.set(true)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("233.8")
        untilBuild.set("262.*") // 兼容性范围：2023.3.8 - 2026.2
        changeNotes.set(
            runCatching {
                val fullText = File(projectDir, "src/main/resources/changelog.html").readText()
                // 仅截取最近版本的更新日志，避免超出 JetBrains Marketplace 数据库 TEXT 限制 (65535 字节)
                fun extractRecent(block: String, count: Int = 2): String {
                    val splits = Regex("(?i)<h3>").findAll(block).toList()
                    return if (splits.size > count) {
                        block.substring(0, splits[count].range.first).trimEnd() + "\n<p><i>... More in CHANGELOG ...</i></p>\n"
                    } else block
                }
                val enIdx = fullText.indexOf("<!--LANG:EN-->")
                val zhIdx = fullText.indexOf("<!--LANG:ZH-->")
                val endIdx = fullText.indexOf("<!--LANG:END-->").let { if (it >= 0) it else fullText.length }
                if (enIdx >= 0 && zhIdx > enIdx) {
                    val enBlock = extractRecent(fullText.substring(enIdx, zhIdx))
                    val zhBlock = extractRecent(fullText.substring(zhIdx, endIdx))
                    "$enBlock\n<hr/>\n$zhBlock"
                } else {
                    extractRecent(fullText, 2)
                }
            }.getOrElse { "DeployX changelog. See CHANGELOG.md for details." }
        )
    }

    // 发布 Token：优先环境变量，其次 gradle.properties，若 GRADLE_USER_HOME 被重定向则回退尝试读取 ~/.gradle/gradle.properties
    val publishToken: String = System.getenv("PUBLISH_TOKEN")?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("publishToken").orNull?.takeIf { it.isNotBlank() }
        ?: runCatching {
            val userProps = File(System.getProperty("user.home"), ".gradle/gradle.properties")
            if (userProps.exists()) {
                val p = Properties().apply { userProps.reader().use { load(it) } }
                p.getProperty("publishToken")
            } else null
        }.getOrNull() ?: ""
    publishPlugin {
        token.set(publishToken)
        // 可选：指定发布渠道，默认是 stable
        // channels.set(listOf("beta"))
    }
    buildSearchableOptions {
        enabled = false
    }

    wrapper {
        gradleVersion = "8.4"
    }
}
