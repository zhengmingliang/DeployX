plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij") version "1.16.0"
}

group = "com.alianga.idea.filesync"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // SSH connection
    implementation("com.github.mwiede:jsch:2.28.3")

    // JSON processing
    implementation("com.google.code.gson:gson:2.10.1")
}

intellij {
    // 使用本地已安装的 IntelliJ IDEA Ultimate（避免下载 ~700MB SDK）
    localPath.set("/home/zml/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate")
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
