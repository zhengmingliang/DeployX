plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij") version "1.16.0"
}

group = "com.alianga.idea.deploy"
version = "1.0.1"

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
        sinceBuild.set("223")
        untilBuild.set("262.*")
        changeNotes.set(
            """
            <![CDATA[
                <ul>
                    <li>v1.0.1 - 国际化支持与语言切换
                        <ul>
                            <li>新增界面语言设置（Settings &gt; Tools &gt; DeployX &gt; Language），支持 English / 简体中文 / 跟随系统，免重启切换</li>
                            <li>修复英文状态下语言设置菜单与右键菜单显示原始 key（如 %settings.language.configurableName）的问题</li>
                            <li>修复切换为中文后已打开的工具窗口面板未刷新文案的问题，现可即时切换</li>
                            <li>右键菜单 Action 文案随当前语言实时刷新</li>
                        </ul>
                    </li>
                    <li>v1.0.0 - Initial release
                        <ul>
                            <li>Server management with SSH/password authentication</li>
                            <li>Directory mapping between local and remote paths</li>
                            <li>Incremental sync using rsync</li>
                            <li>Auto-backup and remote extraction</li>
                            <li>Progress tracking and history</li>
                        </ul>
                    </li>
                </ul>
            ]]>
            """.trimIndent()
        )
    }

    // 安全读取 Token：优先环境变量，其次 gradle.properties，找不到就留空（不报错）
    val publishToken: String = System.getenv("PUBLISH_TOKEN") ?: providers.gradleProperty("publishToken").getOrElse("")
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
