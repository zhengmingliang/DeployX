plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij") version "1.16.0"
}

group = "com.alianga.idea.filesync"
version = "1.0.0"

// 本地 IDEA 安装路径（与 localPath 保持一致）
val ideaHome = "/home/zml/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate"

// CloudTerminalRunner/CloudTerminalProcess 位于 terminal 插件的独立 module jar 中，
// plugins.set 不会自动加入，这里手动补充（compileOnly：运行时由 IDE 的 terminal 插件提供）
val terminalCloudJar = "$ideaHome/plugins/terminal/lib/modules/intellij.terminal.cloud.jar"

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
}

intellij {
    // 使用本地已安装的 IntelliJ IDEA Ultimate（避免下载 ~700MB SDK）
    localPath.set(ideaHome)
    // 依赖 terminal 插件，使其核心类在编译时可用（TerminalView/AbstractTerminalRunner 等）
    plugins.set(listOf("org.jetbrains.plugins.terminal"))
    pluginName.set("File Sync Tool")
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
        sinceBuild.set("223")
        untilBuild.set("")
        changeNotes.set(
            """
            <![CDATA[
                <ul>
                    <li>v1.0.0 - Initial release</li>
                    <li>Server management with SSH/password authentication</li>
                    <li>Directory mapping between local and remote paths</li>
                    <li>Incremental sync using rsync</li>
                    <li>Auto-backup and remote extraction</li>
                    <li>Progress tracking and history</li>
                </ul>
            ]]>
            """.trimIndent()
        )
    }

    buildSearchableOptions {
        enabled = false
    }

    wrapper {
        gradleVersion = "8.4"
    }
}
