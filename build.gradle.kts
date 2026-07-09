plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.10"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.alianga.idea.deploy"
version = "1.0.3"

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
            """
                <h3>v1.0.3 - Transfer Enhancements & Multi-Server Deploy</h3>
                <ul>
                    <li><b>📋 History Report Copy/Export:</b> Added "Copy Report" and "Export Report" buttons to the History tab - copy/export the Markdown report from any history entry (not just the latest one)</li>
                    <li><b>🔍 SFTP Dry-Run Support:</b> SFTP fallback mode now supports dry-run preview (listing files that would be uploaded without actual transfer). Preview sync works even without rsync installed</li>
                    <li><b>🔄 Transfer Auto-Retry:</b> Transfers now automatically retry up to 3 times (2s interval) on network/connection failures. Authentication failures and missing-path errors are not retried</li>
                    <li><b>🔔 System Notifications:</b> After batch upload/deploy completes, an OS-level system tray notification is shown (configurable in settings). Falls back to IDE balloon if system tray is unavailable</li>
                    <li><b>⚙️ General Settings Tab:</b> Added a new "General" tab at the front of the DeployX settings page, consolidating language and the "system notification on transfer complete" toggle (previously buried in the rsync tab) into one place, ready for more general preferences to be added later</li>
                    <li><b>🏷️ Server Groups & Tags:</b> Servers now support a group field and comma-separated tags. ServerManager exposes <code>getGroups()</code>/<code>getAllTags()</code> for filtering</li>
                    <li><b>📦 Config Import/Export (Encrypted):</b> Export all plugin config (servers with passwords, mappings, scripts) to an AES-256-GCM encrypted JSON file. Import on another machine with password decryption; ID conflicts can be overwritten or imported as new</li>
                    <li><b>⚡ Multi-Server Parallel Deploy:</b> Server selection dialog now supports multi-select. When deploying to multiple servers, groups execute in parallel (thread pool, max 4 concurrent). HistoryManager is thread-safe</li>
                    <li><b>🐛 Server List Empty Fix:</b> Fixed an issue where users saw an empty server management page. The new <code>group</code>/<code>tags</code> fields were null in legacy <code>servers.json</code>, causing an NPE in <code>server.copy()</code> that silently wiped the server list. A <code>ServerConfigDeserializer</code> now backfills missing/null fields</li>
                </ul>

                <h3>v1.0.2 - Script Library & Windows rsync Enhancements</h3>
                <ul>
                    <li><b>📚 Script Export:</b> Export now adapts to selection - exports all scripts when nothing is selected, and only the selected scripts (as a JSON array) when multiple are selected</li>
                    <li><b>🗑️ Script Multi-Delete:</b> Deleting now removes all selected scripts at once (previously only the first selected was deleted)</li>
                    <li><b>📥 Script Import - Conflict Handling:</b> On import, if a script id already exists, the user is prompted to either overwrite the duplicate or import all as new scripts</li>
                    <li><b>🔐 SSH_ASKPASS Fallback:</b> When rsync is present but sshpass is missing (common on Windows), password auth automatically falls back to the SSH_ASKPASS mechanism - no SFTP downgrade needed</li>
                    <li><b>⬇️ rsync One-Click Download & Install (Windows):</b> When rsync is not detected on Windows, a "Download &amp; Install rsync" button appears in settings; it auto-downloads (GitHub releases, with China mirror fallback), extracts to <code>~/.deployx/rsync-win/</code>, and configures the path automatically. On failure, manual download links are provided</li>
                    <li><b>💡 First-Run rsync Install Prompt (Windows):</b> On the first transfer with rsync missing, a dialog asks whether to auto-install. "Yes" downloads and installs; "No" remembers the choice and falls back to SFTP silently (never asks again). <code>RSYNC_ONLY</code> mode also degrades to SFTP after the user declines</li>
                    <li><b>🪟 Windows rsync Optimization:</b> Fixed rsync failing on Windows Cygwin builds:
                        <ul>
                            <li>Quote the ssh command passed to <code>-e</code> so it is not split incorrectly</li>
                            <li>Convert Windows drive paths (e.g. <code>C:\Users\...</code>) to Cygwin style (<code>/cygdrive/c/Users/...</code>) to avoid the drive letter being mistaken for a remote host</li>
                            <li>Use <code>--files-from=FILE</code> with a temporary file (auto-deleted after use) instead of stdin piping</li>
                        </ul>
                    </li>
                    <li><b>🐛 Windows rsync Password Auth Fix:</b> Fixed <code>exit code 12</code> / <code>0 bytes received</code> when syncing with password authentication on Windows without sshpass (three root causes, all verified and fixed):
                        <ul>
                            <li><b>askpass script format:</b> Minimal Cygwin rsync packages lack <code>sh.exe</code>; cygwin ssh invokes askpass via <code>posix_spawnp</code> which does not parse shebangs, so <code>.sh</code> scripts fail with <code>No such file or directory</code>. Switched to a <code>.cmd</code> askpass (<code>@type "path"</code>) executed via <code>cmd.exe /c</code></li>
                            <li><b>wrong ssh picked:</b> rsync <code>-e "ssh ..."</code> resolved to the Windows native <code>C:\Windows\System32\OpenSSH\ssh.exe</code> instead of the Cygwin ssh, breaking SSH_ASKPASS compatibility. Now explicitly uses the <code>ssh.exe</code> next to <code>rsync.exe</code></li>
                            <li><b>missing recursion:</b> <code>-a</code> does not imply <code>-r</code> under <code>--files-from</code>, so selecting a directory only created empty subdirectories without transferring the files inside. Now always adds an explicit <code>-r</code></li>
                        </ul>
                    </li>
                    <li><b>🐛 Directory Sync Fix (all platforms):</b> Fixed selecting a directory and syncing only created empty subdirectories without transferring the files inside (same <code>-a</code>/<code>-r</code> root cause as above, affects Linux/macOS too)</li>
                    <li><b>🐛 Update Report Fixes:</b>
                        <ul>
                            <li>Fixed SSH diagnostic lines (e.g. <code>Warning: Permanently added ...</code>) being misidentified as transferred files in the report</li>
                            <li>Fixed rsync directory creation lines (ending with <code>/</code>) being misidentified as transferred files - report now lists only actual files, matching rsync's <code>Number of regular files transferred</code></li>
                            <li>Fixed "Actually updated files" showing selected directories or unchanged files when 0 files were transferred (incremental skip) - now shows "No files changed" instead</li>
                        </ul>
                    </li>
                    <li><b>🎨 SSH Clean-up:</b> Added <code>-o UserKnownHostsFile=/dev/null</code> to suppress the <code>Could not create directory '/home/&lt;user&gt;/.ssh'</code> warning from Cygwin ssh on minimal installs</li>
                    <li><b>🎨 Alt+Shift+Z Menu Fix:</b> Fixed the quick menu popping up in the wrong IDEA window when multiple project windows are open - now uses <code>guessBestPopupLocation(dataContext)</code> for correct positioning</li>
                    <li><b>🎨 Changelog Unification:</b> Synced <code>build.gradle.kts</code> and <code>plugin.xml</code> changelog content - plugin marketplace now shows the full bilingual version</li>
                </ul>

                <h3>v1.0.1 - Internationalization & Enhancements</h3>
                <ul>
                    <li><b>🌐 Internationalization:</b> Full bilingual support (English / 简体中文)</li>
                    <li><b>🌐 Language Switch:</b> Added language settings page - switch language without restart</li>
                    <li><b>🌐 Dynamic Refresh:</b> All UI texts update immediately after language switch, including tool windows and dialogs</li>
                    <li><b>🌐 Right-click Menu:</b> Action texts dynamically refresh based on current language</li>
                    <li><b>✨ New Feature:</b> Added <code>Alt+Shift+Z</code> quick action menu - fast access to DeployX operations without right-click</li>
                    <li><b>✨ New Feature:</b> Added shortcut keys for common operations:
                        <ul>
                            <li><code>Alt+Shift+D</code> / <code>Alt+Shift+2</code>: Deploy</li>
                            <li><code>Ctrl+Alt+Y</code>: Sync to Server</li>
                            <li><code>Ctrl+Alt+Shift+P</code> / <code>Alt+Shift+3</code>: Quick Push</li>
                            <li><code>Ctrl+Alt+T</code> / <code>Alt+Shift+4</code>: Open SSH Terminal</li>
                        </ul>
                    </li>
                    <li><b>🎨 Improvement:</b> Optimized update report format - now shows actual transferred files inside directories (e.g., <code>lib/api-base.jar</code> instead of just <code>lib/</code>)</li>
                    <li><b>🎨 Improvement:</b> Added backup file path display in update report</li>
                    <li><b>🎨 Improvement:</b> Enhanced rsync output parsing logic - accurately identifies file lines, filters progress info and statistics</li>
                    <li><b>🎨 UI Improvement:</b> Adjusted right-click menu order, moved Deploy before Sync for better UX</li>
                    <li><b>🐛 Bug Fix:</b> Fixed language setting menu showing raw keys (e.g., <code>%settings.language.configurableName</code>) in English mode</li>
                    <li><b>🐛 Bug Fix:</b> Fixed PluginException when opening settings page</li>
                </ul>

                <h3>v1.0.0 - Initial Release</h3>
                <ul>
                    <li>🚀 Initial public release</li>
                    <li>SSH server management with password/key authentication</li>
                    <li>Directory mapping with unique IDs</li>
                    <li>Smart rsync + SFTP fallback transfer</li>
                    <li>One-click deploy with auto backup and extract</li>
                    <li>Remote command execution (pre/post commands)</li>
                    <li>SSH terminal integration</li>
                    <li>Script library support</li>
                    <li>Per-server logs and history</li>
                    <li>Markdown update report generation</li>
                </ul>

                <br>
                <h4>中文更新说明</h4>

                <h3>v1.0.3 - 传输增强与多服务器并行部署</h3>
                <ul>
                    <li><b>📋 历史记录复制/导出报告：</b>历史记录 Tab 新增"复制报告"和"导出报告"按钮，可从任意历史记录复制/导出 Markdown 报告（不再仅限最新一次）</li>
                    <li><b>🔍 SFTP 支持 dry-run：</b>SFTP 降级模式现在支持 dry-run 预览（列出待上传文件而不实际传输），预览同步不再强制要求 rsync</li>
                    <li><b>🔄 传输失败自动重试：</b>传输失败时自动重试最多 3 次（间隔 2 秒），仅对网络/连接类失败重试，认证失败和路径不存在不重试</li>
                    <li><b>🔔 部署后系统通知：</b>批量上传/部署完成后弹出 OS 级系统托盘通知（可在设置中开关），系统托盘不可用时回退到 IDE 气泡通知</li>
                    <li><b>⚙️ 新增「通用」设置 Tab：</b>在 DeployX 主设置页最前面新增「通用」Tab，将语言设置和「部署后系统通知」开关（原埋在 rsync Tab 内）集中到一处，后续可继续扩展更多通用设置项</li>
                    <li><b>🏷️ 服务器分组/标签：</b>服务器新增分组字段和逗号分隔标签，ServerManager 提供 <code>getGroups()</code>/<code>getAllTags()</code> 用于过滤</li>
                    <li><b>📦 配置导入导出（加密）：</b>一键导出全部插件配置（服务器含密码、映射、脚本）为 AES-256-GCM 加密 JSON 文件，在另一台机器上用密码解密导入；ID 冲突可选覆盖或新增</li>
                    <li><b>⚡ 多服务器并行部署：</b>服务器选择对话框支持多选，部署到多个服务器时分组并行执行（线程池，最多 4 并发），HistoryManager 线程安全</li>
                    <li><b>🐛 服务器列表为空修复：</b>修复"服务器管理"页面看不到任何已保存服务器的问题。新增的 <code>group</code>/<code>tags</code> 字段在旧 <code>servers.json</code> 中为 null，导致 <code>server.copy()</code> 抛 NPE 并被静默吞掉、返回空列表。新增 <code>ServerConfigDeserializer</code> 对缺失/null 字段做兜底</li>
                </ul>

                <h3>v1.0.2 - 脚本库增强与 Windows rsync 优化</h3>
                <ul>
                    <li><b>📚 脚本导出优化：</b>导出逻辑适配选中状态 - 未选中任何脚本时导出全部脚本，选中多个脚本时仅导出选中的脚本（JSON 数组形式）</li>
                    <li><b>🗑️ 脚本批量删除：</b>删除操作现在可一次性删除所有选中的脚本（此前只会删除选中的第一个）</li>
                    <li><b>📥 脚本导入冲突处理：</b>导入时若脚本 id 已存在，提示用户选择「覆盖重复项」或「全部作为新脚本新增」</li>
                    <li><b>🔐 SSH_ASKPASS 密码认证回退：</b>已安装 rsync 但缺少 sshpass 时（Windows 常见），密码认证自动回退使用 SSH_ASKPASS 机制，无需降级 SFTP</li>
                    <li><b>⬇️ rsync 一键下载安装（Windows）：</b>设置面板检测到未安装 rsync 时显示「一键下载安装」按钮，自动下载（GitHub releases，失败切换国内镜像）、解压到 <code>~/.deployx/rsync-win/</code>、自动配置路径；下载失败提供手动下载链接</li>
                    <li><b>💡 首次传输安装提示（Windows）：</b>首次传输时未检测到 rsync 则弹窗询问是否自动安装。选「是」下载安装；选「否」记住选择并静默降级 SFTP（不再提示）。<code>RSYNC_ONLY</code> 模式下用户拒绝后也降级 SFTP</li>
                    <li><b>🪟 Windows rsync 同步优化：</b>修复 Windows Cygwin 版 rsync 同步失败的问题：
                        <ul>
                            <li><code>-e</code> 后的 ssh 命令整体加引号，避免被错误拆分</li>
                            <li>Windows 盘符路径（如 <code>C:\Users\...</code>）转换为 Cygwin 风格（<code>/cygdrive/c/Users/...</code>），避免盘符被误识别为远程主机</li>
                            <li><code>--files-from</code> 改用临时文件（使用完自动删除）替代 stdin 传入</li>
                        </ul>
                    </li>
                    <li><b>🐛 Windows rsync 密码认证修复：</b>修复 Windows 无 sshpass 时密码认证同步报 <code>exit code 12</code> / <code>0 bytes received</code> 的问题（三个根因，均已实测验证修复）：
                        <ul>
                            <li><b>askpass 脚本格式：</b>精简 Cygwin rsync 包不含 <code>sh.exe</code>，cygwin ssh 通过 <code>posix_spawnp</code> 调用 askpass（不解析 shebang），<code>.sh</code> 脚本因找不到 <code>/bin/sh</code> 报 <code>No such file or directory</code>。改用 <code>.cmd</code> 脚本（<code>@type "路径"</code>），由 <code>cmd.exe /c</code> 执行</li>
                            <li><b>ssh 来源错误：</b>rsync <code>-e "ssh ..."</code> 用裸 <code>ssh</code> 时会找到 Windows 原生 <code>C:\Windows\System32\OpenSSH\ssh.exe</code> 而非 cygwin ssh，导致 SSH_ASKPASS 机制不兼容。改为显式使用 <code>rsync.exe</code> 同目录下的 <code>ssh.exe</code></li>
                            <li><b>缺少递归：</b><code>-a</code> 在 <code>--files-from</code> 模式下不会展开为 <code>-r</code>，选中目录同步时只创建空子目录、不传文件。现已显式补加 <code>-r</code></li>
                        </ul>
                    </li>
                    <li><b>🐛 目录同步修复（全平台）：</b>修复选中目录同步时只创建空子目录、不传输目录内文件的问题（同上 <code>-a</code>/<code>-r</code> 根因，Linux/macOS 同样受影响）</li>
                    <li><b>🐛 更新报告修复：</b>
                        <ul>
                            <li>修复 SSH 诊断行（如 <code>Warning: Permanently added ...</code>）被误判为传输文件、拼成远程路径写入报告的问题</li>
                            <li>修复 rsync 目录创建行（以 <code>/</code> 结尾）被误判为传输文件的问题 - 报告现在只列出实际传输的文件，与 rsync 统计 <code>Number of regular files transferred</code> 一致</li>
                            <li>修复增量同步跳过所有文件（0 传输）时报告"实际更新的文件"回退到选中路径（含目录）的问题 - 现在显示"无文件变更"提示</li>
                        </ul>
                    </li>
                    <li><b>🎨 SSH 优化：</b>新增 <code>-o UserKnownHostsFile=/dev/null</code>，消除精简 Cygwin 环境下 <code>Could not create directory '/home/&lt;user&gt;/.ssh'</code> 警告</li>
                    <li><b>🎨 Alt+Shift+Z 菜单修复：</b>修复多个 IDEA 项目窗口时快捷菜单弹到错误窗口的问题 - 改用 <code>guessBestPopupLocation(dataContext)</code> 正确定位</li>
                    <li><b>🎨 Changelog 统一：</b>统一 <code>build.gradle.kts</code> 与 <code>plugin.xml</code> 的 changelog 内容，插件市场显示完整中英文双语版本</li>
                </ul>

                <h3>v1.0.1 - 国际化与体验优化</h3>
                <ul>
                    <li><b>🌐 国际化：</b>完整的中英文双语界面支持</li>
                    <li><b>🌐 语言切换：</b>新增语言设置页面，支持免重启即时切换语言（English / 简体中文 / 跟随系统）</li>
                    <li><b>🌐 动态刷新：</b>切换语言后，所有 UI 文案立即刷新，包括工具窗口和对话框</li>
                    <li><b>🌐 右键菜单：</b>操作文案根据当前语言动态刷新</li>
                    <li><b>✨ 新功能：</b>新增 <code>Alt+Shift+Z</code> 快捷操作菜单，无需右键即可快速执行 DeployX 常用操作</li>
                    <li><b>✨ 新功能：</b>新增常用操作快捷键：
                        <ul>
                            <li><code>Alt+Shift+D</code> / <code>Alt+Shift+2</code>：完整部署</li>
                            <li><code>Ctrl+Alt+Y</code>：同步上传</li>
                            <li><code>Ctrl+Alt+Shift+P</code> / <code>Alt+Shift+3</code>：快速推送</li>
                            <li><code>Ctrl+Alt+T</code> / <code>Alt+Shift+4</code>：打开 SSH 终端</li>
                        </ul>
                    </li>
                    <li><b>🎨 优化：</b>优化更新报告格式 - 上传目录时，显示目录下实际更新的文件（如 <code>lib/api-base.jar</code>），而非仅显示目录名</li>
                    <li><b>🎨 优化：</b>更新报告中显示备份文件的具体路径</li>
                    <li><b>🎨 优化：</b>增强 rsync 输出解析逻辑，精确识别文件名行，过滤进度信息和统计行</li>
                    <li><b>🎨 UI 优化：</b>调整右键菜单顺序，将 Deploy 移到 Sync 前面，更符合使用习惯</li>
                    <li><b>🐛 修复：</b>修复英文状态下语言设置菜单显示原始 key 的问题</li>
                    <li><b>🐛 修复：</b>修复打开设置页面时的 PluginException 异常</li>
                </ul>

                <h3>v1.0.0 - 初始版本</h3>
                <ul>
                    <li>🚀 首次公开发布</li>
                    <li>SSH 服务器管理，支持密码/密钥认证</li>
                    <li>本地/远程目录映射，唯一 ID 管理</li>
                    <li>rsync 增量同步 + SFTP 自动降级</li>
                    <li>一键部署：自动备份、上传、解压、执行命令</li>
                    <li>远程命令执行（前置/后置命令）</li>
                    <li>SSH 终端集成</li>
                    <li>脚本库支持</li>
                    <li>按服务器拆分的日志与历史记录</li>
                    <li>Markdown 更新报告生成</li>
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
