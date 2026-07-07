plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.alianga.idea.deploy"
version = "1.0.2"

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
        sinceBuild.set("233")
        untilBuild.set("262.*") // 兼容性范围：2023.3 - 2026.2
        changeNotes.set(
            """
                <ul>
                    <li>v1.0.2 - 脚本库增强与 Windows rsync 优化
                        <ul>
                            <li><b>脚本导出优化：</b>未选中任何脚本时导出全部脚本，选中多个脚本时仅导出选中的脚本（JSON 数组）</li>
                            <li><b>脚本批量删除：</b>支持一次性删除所有选中的脚本（此前仅删除第一个选中的）</li>
                            <li><b>脚本导入冲突处理：</b>导入时若 id 已存在，提示用户「覆盖重复项」或「全部作为新脚本新增」</li>
                            <li><b>Windows rsync 同步优化：</b>
                                <ul>
                                    <li><code>-e</code> 后的 ssh 命令整体加引号，避免被错误拆分</li>
                                    <li>Windows 盘符路径（如 <code>C:\Users\...</code>）转换为 Cygwin 风格（<code>/cygdrive/c/Users/...</code>），避免盘符被误识别为远程主机</li>
                                    <li><code>--files-from</code> 改用临时文件（自动删除）替代 stdin 传入</li>
                                </ul>
                            </li>
                            <li><b>Windows rsync 密码认证修复：</b>修复 Windows 无 sshpass 时密码认证同步报 <code>exit code 12</code> / <code>0 bytes received</code>（三个根因，均已实测验证修复）：
                                <ul>
                                    <li><b>askpass 脚本格式：</b>精简 Cygwin rsync 包不含 <code>sh.exe</code>，cygwin ssh 用 <code>posix_spawnp</code> 调用 askpass（不解析 shebang），<code>.sh</code> 脚本报 <code>No such file or directory</code>。改用 <code>.cmd</code> 脚本（<code>@type "路径"</code>）由 <code>cmd.exe /c</code> 执行</li>
                                    <li><b>ssh 来源错误：</b>rsync <code>-e "ssh ..."</code> 用裸 <code>ssh</code> 会找到 Windows 原生 <code>ssh.exe</code> 而非 cygwin ssh，导致 SSH_ASKPASS 不兼容。改为显式使用 <code>rsync.exe</code> 同目录下的 <code>ssh.exe</code></li>
                                    <li><b>缺少递归：</b><code>-a</code> 在 <code>--files-from</code> 模式下不展开为 <code>-r</code>，选中目录只创建空子目录不传文件。现已显式补加 <code>-r</code></li>
                                </ul>
                            </li>
                            <li><b>目录同步修复（全平台）：</b>修复选中目录同步时只创建空子目录、不传输目录内文件的问题（同 <code>-a</code>/<code>-r</code> 根因，Linux/macOS 同样受影响）</li>
                            <li><b>SSH 优化：</b>新增 <code>-o UserKnownHostsFile=/dev/null</code>，消除精简 Cygwin 环境下 <code>Could not create directory '/home/&lt;user&gt;/.ssh'</code> 警告</li>
                        </ul>
                    </li>
                    <li>v1.0.1 - 兼容性修复、密码存储增强与体验优化
                        <ul>
                            <li><b>IDE 兼容性优化：</b>修复 IntelliJ IDEA 2024.1+ (IU-241.19416.15) 版本 CloudTerminalRunner 构造函数不兼容问题，支持从 2022.3 到 2025.1 的所有版本</li>
                            <li><b>密码存储三层架构：</b>
                                <ul>
                                    <li>内存缓存 - 加速频繁访问</li>
                                    <li>系统密钥链 (PasswordSafe) - 安全持久化</li>
                                    <li>AES 加密本地备份 - 确保重启后密码不丢失，解决沙箱环境密钥链失效问题</li>
                                </ul>
                            </li>
                            <li><b>用户专属加密：</b>密码备份文件使用 user.home + user.name 派生的 AES-256 密钥加密，防止跨用户密码泄露</li>
                            <li><b>自动恢复机制：</b>系统密钥链加载失败时自动从加密备份恢复，并尝试重新保存到系统密钥链</li>
                            <li>新增界面语言设置（Settings &gt; Tools &gt; DeployX &gt; Language），支持 English / 简体中文 / 跟随系统，免重启切换</li>
                            <li>服务器密码通过 IntelliJ PasswordSafe 加密存储，不再写入 servers.json 明文；兼容旧版明文密码，首次加载自动迁移</li>
                            <li>服务器编辑对话框：内联测试连接按钮、认证方式联动（PASSWORD/KEY 切换显示对应字段）、密钥文件输入框支持文件选择器</li>
                            <li>服务器选择对话框：新增搜索框（按 id/名称/主机/用户名过滤）、双击确认、键盘自动聚焦、列表尺寸加大</li>
                            <li>服务器/映射设置面板新增搜索过滤，Script 搜索统一为实时过滤</li>
                            <li>抽取 8 个文件约 143 处硬编码中文到 i18n 资源，英文用户不再看到中文残留</li>
                            <li>DeployService 移除 9 处 !! 强制非空，避免 NPE 隐患</li>
                            <li>抽取 AbstractDeployAction 模板方法，三个 Action 代码量减半</li>
                            <li>RemotePathChooserDialog 改用 ProgressManager，支持取消且避免对话框关闭后访问失效 UI</li>
                            <li>ServerManager / MappingManager 改用 CopyOnWriteArrayList，避免并发修改异常</li>
                            <li>修复服务器选择对话框过滤后选中索引越界的问题</li>
                            <li>删除 SshConnection 中遗留的 println/printStackTrace 调试输出</li>
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
