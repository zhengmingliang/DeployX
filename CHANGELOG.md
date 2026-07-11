# DeployX Changelog

## [1.0.5] - 2026-07-11


### ✨ 新功能
- **部署回滚**：在历史标签页选中有备份的部署记录可一键回滚
  - **回滚确认对话框**：显示服务器、目标路径、备份文件、文件数量、部署时间等关键信息，支持预览备份文件列表
  - **执行进度对话框**：实时显示回滚状态、进度条、执行日志；支持取消（回滚不可取消；完成后自动刷新历史、更新报告输出成功通知
  - **回滚服务**：基于 tar 解压恢复，支持错误处理和日志记录；服务层异常捕获 + 底层复用 SshConnection 执行远程命令
  - **历史集成**：仅 DEPLOY 类型、`canRollback=true`、`backupFilePath` 非空的记录才显示回滚按钮
  - **视觉标识**：可回滚的历史记录前自动添加 🔄 图标，直观识别
  - **完整 i18n**：中英文双语 Bundle 覆盖所有 UI 文案

- **远程文件浏览器**：在 IDE 侧边栏以树形结构浏览远程服务器文件系统，支持：
  - **查看**：懒加载目录树，显示文件大小/修改时间/图标；切换服务器、路径跳转（支持历史记忆）、新建/删除目录、刷新
  - **编辑**：双击远程文本文件直接在 IDE 主编辑器中打开，完整语法高亮；`Ctrl+S` 自动写回远程服务器（基于 `LightVirtualFile` + `FileDocumentManagerListener`）；超过 5MB 的文件拒绝打开并提示改用下载
  - **下载**：右键下载文件/目录到本地，或从树中拖拽文件到项目视图直接下载
  - **上传**：从项目视图或系统文件管理器拖拽本地文件/目录到浏览器树，自动上传到目标远程目录
  - **路径历史**：按服务器记忆成功进入过的路径（最多 20 条），可快速跳转、删除单条或清空
  - **自动映射与路径记忆**：首次打开按当前项目路径匹配映射自动定位远程目录；记忆每台服务器上次打开的目录
  - 侧边栏工具窗口（非模态，右键菜单/Alt+Shift+Z/工具窗口按钮均可打开），浏览远程文件不阻塞本地编辑
  - 持久化 SFTP 通道复用 + 自动重连，浏览延迟低；通道异常时自动重连重试
  - 完整中英文双语支持


### 🐛 Bug修复
- **操作按钮占空间**：工具栏按钮改为紧凑无边框风格（icon-only），节省路径栏空间；历史标签页按钮同步改为无边框紧凑样式
- **窗口图标区分**：新增独立的远程文件浏览器图标
- **路径切换报错**：SFTP 通道异常时新增自动重连+重试机制，修复物理路径时偶发 `inputstream is closed` 错误
- **密钥认证文件同步失败**：修复使用密钥认证的服务器在文件同步/部署时报 `Permissions 0644 for 'xxx' are too open` 的问题。根因：终端使用 JSch 库（不检查文件权限），而 rsync 使用系统 ssh（OpenSSH 强制要求私钥权限为 `0600`）。新增自动权限修复机制：检测到私钥权限过松时自动创建临时副本并设置正确权限（仅所有者可读写），传输完成后自动清理临时文件。用户无需手动修改密钥文件权限即可正常部署
- **回滚对话框 Bundle 缺失**：修复 `rollback.dialog.fileCount.unknown` 等 18 个国际化 Key 未定义导致显示原始 Key 的问题；补全中英文双语资源
- **RollbackService 空指针**：修复 Swing 后台线程中 `serviceOf()` 返回 null 的问题。根因：IDEA 服务管理器在非 EDT 线程获取服务存在时序问题。改为构造函数中预取服务实例、后台线程复用，同时 `@Service` 注解改为 `@Service(Service.Level.APP)` 确保注册正确
- **更新报告混入 stderr**：增强 rsync 文件传输行解析逻辑，排除 mise 工具输出、shell 命令标记（`$` / `>`）、版本信息行（`vX.Y.Z`）等非文件输出，确保报告文件列表准确


### 🛠 重构与体验优化
- **回滚功能细节优化**：
  - 移除对话框中 `<b>` 粗体标签，视觉风格与 IDE 统一
  - 文件数量"未知"改为更友好的提示文案："待确认（回滚执行后将显示实际文件数）"
  - 统一时间格式为 `yyyy-MM-dd HH:mm:ss`，所有历史记录时间显示更规范
  - 备份文件命名规则优化：`{源目录名}_bak_{时间戳}.tar.gz` 替代泛用的 `backup_*.tar.gz`，便于识别来源

- **脚本编辑器复用 IDEA 编辑器能力**：将项目中所有脚本/命令编辑场景从 Swing 原生组件（`JBTextArea` + 手写 `LineNumberGutter`）迁移到 IDEA 平台 `EditorTextField`，统一获得语法高亮、行号、代码折叠、当前行高亮、主题自适应等编辑器能力。

  改造方案对比图见 [docs/script-editor-refactor.svg](docs/script-editor-refactor.svg)。

  **改造范围（8 处场景）**：
  - 可编辑场景：`ScriptEditDialog` 命令模板编辑区、`CommandFieldWithScriptButton` 多行编辑、`CommandFullscreenDialog` 全屏编辑
  - 只读预览场景：`ScriptRunDialog` 命令预览、`ScriptTabPanel` 脚本库预览
  - `MappingEditDialog` 的前置/后置命令输入自动跟随 `CommandFieldWithScriptButton` 升级

  **新增**：
  - `ScriptEditorFactory`：统一创建可编辑/只读 `EditorTextField`，自动探测 Shell Script FileType（IDEA Ultimate 默认提供），未找到时降级 PlainText，保证不依赖外部插件
  - 内置行号、滚动条、当前行高亮；project 为 null 时手动补设语法高亮器，确保无 project 上下文也有高亮
  - 修复 `EditorTextField` 在 `DialogWrapper` 中回车键触发"确定"按钮的问题（拦截 Enter 手动换行）
  - 修复粘贴多行命令变成一行的问题（强制多行模式 `setOneLineMode(false)`）
  - 修复 Tab 键被焦点切换拦截的问题（拦截 Tab 手动插入缩进）

  **移除**：
  - 删除 `LineNumberGutter.kt`（手写行号边栏，`EditorTextField` 内置 gutter 完全替代）
  - `CommandFullscreenDialog` 移除 `sourceFont` 参数（编辑器字体由平台统一管理）


---

## [1.0.4] - 2026-07-10


### ✨ 新功能
- **批量部署**：支持同时选择多个服务器，文件将并行上传到所有选中的服务器，大幅提升多环境部署效率；服务器选择对话框支持按住 Ctrl/Shift 多选，终端也支持同时打开多个服务器连接

- **导出配置时包含 SSH 密钥文件**：导出加密配置时，若存在使用密钥认证的服务器，会提示用户是否将密钥文件内容一并加密导出。导入到另一台机器时自动解密并保存到本地密钥目录（`~/.deploy-x/keys/`），服务器配置的密钥路径自动指向本地文件，无需手动复制密钥。若不选择导出密钥文件，仍按原有方式仅导出服务器配置（密钥路径保留但内容不导出）
- **远程路径选择对话框新增「新建目录」按钮**：在添加/编辑映射、侧边栏操作的远程路径选择对话框中，新增新建目录功能，支持直接在远程服务器上创建目录，无需切换到终端手动操作，创建成功后自动刷新目录列表并选中新目录。
- **服务器下拉列表宽度自适应**：在添加/编辑映射对话框中，目标服务器下拉列表自动适配最长服务器名称的宽度，确保完整显示服务器ID和名称，避免内容截断。



### 🐛 Bug 修复
- **配置导入后服务器丢失密码**：修复导入加密配置后，服务器密码在内存中丢失导致 SSH 连接失败的问题。根因：`ConfigExporter.importConfig` 在保存密码到 PasswordSafe / `.passwords.dat` 后，传给 `ServerManager` 的是 `server.copy(password = "")` 的副本，导致内存中的 `ServerConfig.password` 为空，SSH 密码认证失败。现已改为直接传入带密码的 `ServerConfig`，`ConfigManager.saveServers()` 仍会在写入 `servers.json` 时清空密码字段，行为与现有逻辑完全一致
- **rsync 拉取方向反转**：修复「从服务器拉取」使用 rsync 模式时，生成的 `--files-from` 命令把本地目录当作源、远程目录当作目标，实际变成了上传而非拉取的问题。根因：`buildRsyncCommand` / `buildRsyncFilesFromCommand` 始终固定「本地→远程」的源/目标顺序，未根据 `SyncDirection.PULL` 交换。现已改为按 `options.direction` 动态交换：PULL 时 `远程源 → 本地目标`，PUSH 时维持 `本地源 → 远程目标`，单文件 `pull` 与批量 `pullFilesFrom` 均正确
- **拉取日志不清晰**：修复「从服务器拉取」的执行日志误用「预览分组」标题、信息不明确的问题。下载分组现使用独立的「从服务器拉取分组」标题，并明确打印「拉取方向: 远程服务器 → 本地」「远程源目录 (服务器)」「本地目标目录」，与上传分组的「上传方向: 本地 → 远程」对称，日志一眼可辨操作方向与源/目标
- **Alt+Shift+Z 菜单缺少「从服务器拉取」**：修复 `Alt+Shift+Z` 快捷操作菜单中没有「从服务器拉取」项的问题。已将 `DeployX.PullFromServer` 加入 `ShowDeployXMenuAction` 的菜单列表（位于「同步到服务器」之后），与右键子菜单保持一致
- **rsync 拉取目录/映射根时文件数为 0**：修复「从服务器拉取」选中目录或映射根（相对路径为空）时，`--files-from` 列表为空条目，rsync 实际传输 0 个文件的问题（日志表现为 `[FILES-FROM]` 后无内容、`Number of regular files transferred: 0`）。根因：`DownloadItem.relativePath` 对目录/映射根场景为空，而 `--files-from` 模式无法处理空条目。现已拆分相对路径——空项走整目录递归拉取（普通 `rsync 远程源/ 本地目标`，自动只传差异文件），非空项仍走 `--files-from` 精确拉取，二者可混合并合并结果。SFTP 降级模式下空相对路径同样改为递归下载整个远程目录

- **历史 Tab 展示空白**：修复侧边面板「历史」Tab 在工具窗口初次构建时因 `HistoryManager` 尚未就绪而加载到空列表、之后一直显示为空白（无任何内容）的问题。现改为：① 每次切换到「历史」Tab 时自动重新加载最新记录；② 列表为空时显示明确的占位提示「暂无历史记录」，替代原先令人困惑的空白界面
- **「从服务器拉取」不记录历史**：修复「从服务器拉取」（双向同步的下载路径）执行后历史面板没有任何记录的问题。根因：`downloadBatch` / `processDownloadGroup` 只负责传输，从不调用 `HistoryManager.addRecord`，且 `HistoryRecord.OperationType` 没有拉取类型。现已新增 `PULL` 类型，并在拉取成功/失败后写入历史（仅实际拉取写，dry-run 预览不写），历史列表摘要对 PULL 显示「远程 → 本地」方向；历史「重新部署」对 PULL 记录改为执行「重新拉取」（远程 → 本地），更新报告 operationType 新增 `PULL` 渲染
- **「同步到服务器 / 推送到服务器」不记录历史**：修复右键「同步到服务器」「推送到服务器」执行后历史面板没有记录的问题。根因：这两条路径走 `uploadBatch` → `processUploadGroup`，而历史写入只存在于 `deployBatch` 的 `processDeployGroup` 中。现已在 `processUploadGroup` 内新增 `saveUploadGroupHistory`（type = `UPLOAD`），仅在非 dry-run 的实际同步/推送成功或失败后写入历史；预览（dry-run）明确不写历史
- **历史列表单击误跳转日志页**：修复在「历史」Tab 中单击某条记录会立即跳转到「日志」页签、无法单独选中的问题。根因：`addListSelectionListener` 在单击选中时就触发 `showHistoryDetail()`，其内部 `appendLog` 会切到日志 Tab。现改为 `MouseListener` 双击才查看详情并跳转，「日志」页签；单击仅做选中，不影响后续「重新部署 / 复制报告 / 填充配置」等操作

### 🛠 重构
- **rsync 下载目录统一**：Windows 下 rsync 自动下载解压目录从 `~/.deployx/rsync-win/` 迁移至 `~/.deploy-x/rsync-win/`，与插件其他配置文件（`servers.json`、`.passwords.dat` 等）统一使用 `.deploy-x` 目录。首次访问时自动检测并迁移旧的 `.deployx/rsync-win/` 目录（若存在），用户无需手动操作

---

## [1.0.3] - 2026-07-08

### ✨ 新功能
- **历史记录复制/导出报告**：历史记录 Tab 新增"复制报告"和"导出报告"按钮，可从任意历史记录复制/导出 Markdown 报告（不再仅限最新一次）
- **SFTP 支持 dry-run**：SFTP 降级模式现在支持 dry-run 预览（列出待上传文件而不实际传输），预览同步不再强制要求 rsync
- **传输失败自动重试**：传输失败时自动重试最多 3 次（间隔 2 秒），仅对网络/连接类失败重试，认证失败和路径不存在不重试。`SyncResult` 新增 `attempts` 字段
- **部署后系统通知**：批量上传/部署完成后，当 IDE 窗口未聚焦时弹出 OS 级系统通知，同时始终显示 IDE 气泡通知（可在设置中开关）。Linux 走 libnotify，macOS 走通知中心；Windows 10/11 改用 PowerShell 调 WinRT Toast API（稳定显示在通知中心），弃用平台 `SystemNotifications` 在 Windows 上走的 `java.awt.TrayIcon` 经典托盘气泡（已废弃、`SystemTray.isSupported()` 返回 false 或 AWTException 被吞掉时静默失败）。旧实现因 SVG 图标无法被 ImageIcon 加载也已弃用 `java.awt.SystemTray`
- **新增「通用」设置 Tab**：在 DeployX 主设置页最前面新增「通用」Tab，集中放置语言、系统通知等跨模块通用设置项，后续可继续扩展。语言设置从独立子页并入此处，「部署后系统通知」开关从 rsync Tab 迁入此处（解决该开关埋在 rsync Tab、用户找不到的问题）
- **服务器分组/标签**：服务器新增分组（`group`）字段和逗号分隔标签（`tags`），`ServerManager` 提供 `getGroups()`/`getAllTags()` 用于过滤
- **配置导入导出（加密）**：一键导出全部插件配置（服务器含密码、映射、脚本）为 AES-256-GCM 加密 JSON 文件，在另一台机器上用密码解密导入；ID 冲突可选覆盖或新增
- **多服务器并行部署**：服务器选择对话框支持多选，部署到多个服务器时分组并行执行（线程池，最多 4 并发），`HistoryManager` 加 `synchronized` 保证线程安全
- **命令输入框脚本集成**：上传前/上传后命令输入框统一支持三种操作模式——手动输入命令、从脚本库引用脚本（ScriptRef 标记）、将脚本内容插入到编辑光标位置。该优化统一应用于添加/编辑映射界面和侧边栏操作面板
- **映射编辑对话框脚本输入区域重新设计**：命令输入框从单行升级为多行（带行号边栏 + 代码滚动），字体 13pt Monospaced，对话框 700×800。输入框末端新增全屏按钮，点击后弹出专属全屏编辑对话框（右上角恢复按钮），方便编辑长脚本。全屏/暗色主题下使用 `JBColor` 自动适配

### 🐛 修复
- **服务器列表加载为空**：修复新增服务器 `group`/`tags` 字段后，升级到 1.0.3 的用户打开"服务器管理"看不到任何已保存服务器的问题。根因：旧 `servers.json` 缺失这两个字段，Gson 反序列化时将其设为 `null`（不触发 Kotlin 默认值），随后 `ConfigManager.loadServers` 调用 `server.copy(password = pwd)` 触发 `NullPointerException: parameter group`，异常被吞掉后返回空列表。新增 `ServerConfigDeserializer` 对所有可空/缺失字段做兜底（`group->""`、`tags->[]`），并在 `ConfigManager`/`ConfigExporter` 的 Gson 实例上注册，覆盖配置加载与加密导入两条路径
- **ScriptRef 引用脚本不执行**：修复 `# DeployX ScriptRef: {...}` 标记在 SSH 执行前未被解析、bash 当作注释跳过导致脚本不执行的问题。新增 `ScriptRefResolver`，在 DeployService 的 6 处执行点统一注入解析逻辑——将标记行替换为实际渲染命令后发送到 SSH；脚本不存在时生成 echo 警告而非静默失败

### 🎨 优化改进
- IDE 最低兼容版本从 233 调整为 233.8（IntelliJ IDEA 2023.3.8+）
- **命令输入框交互优化**：ScriptRef 引用标记在文本框中以人类可读格式显示（`#[DeployX] 脚本引用: echo — name=张三` + JSON 标记），替代原始 JSON 字符串；脚本库按钮、全屏/恢复按钮分别使用语义化图标（`AddMulticaret`/`ExpandComponent`/`CollapseComponent`）
- **Dark 主题适配**：行号边栏使用 `JBColor` 自动适配暗色/亮色主题，Dark 下背景为编辑器 gutter 标准暗色（`#313335`），无需手动切换

---

## [1.0.2] - 2026-07-08

### ✨ 新功能
- **脚本导出优化**：导出逻辑适配选中状态 - 未选中任何脚本时导出全部脚本，选中多个脚本时仅导出选中的脚本（JSON 数组形式）
- **脚本批量删除**：删除操作现在可一次性删除所有选中的脚本（此前只会删除选中的第一个）
- **脚本导入冲突处理**：导入时若脚本 id 已存在，提示用户选择「覆盖重复项」或「全部作为新脚本新增」
- **SSH_ASKPASS 密码认证回退**：Windows/Linux 上已安装 rsync 但缺少 sshpass 时，自动回退使用 SSH_ASKPASS 机制提供密码，密码认证的 rsync 仍可正常工作，无需降级 SFTP
- **rsync 一键下载安装（Windows）**：设置面板检测到未安装 rsync 时显示「一键下载安装」按钮，自动下载（GitHub releases，失败切换国内镜像）、解压到 `~/.deploy-x/rsync-win/`、自动配置路径；下载失败提供手动下载链接
- **首次传输安装提示（Windows）**：首次传输时未检测到 rsync 则弹窗询问是否自动安装。选「是」下载安装；选「否」记住选择并静默降级 SFTP（不再提示）。`RSYNC_ONLY` 模式下用户拒绝后也降级 SFTP

### 🪟 Windows rsync 同步优化
- `-e` 后的 ssh 命令整体加引号，避免被 cygwin rsync 错误拆分
- Windows 盘符路径（如 `C:\Users\...`）转换为 Cygwin 风格（`/cygdrive/c/Users/...`），避免盘符被误识别为远程主机
- `--files-from` 改用临时文件（使用完自动删除）替代 stdin 传入

### 🐛 Windows rsync 密码认证修复
修复 Windows 无 sshpass 时密码认证同步报 `exit code 12` / `0 bytes received` 的问题（三个根因，均已实测验证修复）：
- **askpass 脚本格式**：精简 Cygwin rsync 包不含 `sh.exe`，cygwin ssh 通过 `posix_spawnp` 调用 askpass（不解析 shebang），`.sh` 脚本因找不到 `/bin/sh` 报 `No such file or directory`。改用 `.cmd` 脚本（`@type "路径"`），由 `cmd.exe /c` 执行
- **ssh 来源错误**：rsync `-e "ssh ..."` 用裸 `ssh` 时会找到 Windows 原生 `C:\Windows\System32\OpenSSH\ssh.exe` 而非 cygwin ssh，导致 SSH_ASKPASS 机制不兼容。改为显式使用 `rsync.exe` 同目录下的 `ssh.exe`
- **缺少递归**：`-a` 在 `--files-from` 模式下不会展开为 `-r`，选中目录同步时只创建空子目录、不传文件。现已显式补加 `-r`

### 🐛 目录同步修复（全平台）
- 修复选中目录同步时只创建空子目录、不传输目录内文件的问题（同上 `-a`/`-r` 根因，Linux/macOS 同样受影响）

### 🐛 更新报告修复
- **rsync 诊断行误判**：修复 SSH 的 `Warning: Permanently added ...` 等诊断行被误判为文件传输行、拼成远程路径写入报告"实际更新的文件"的问题
- **目录创建行误判**：修复 rsync 输出的目录创建行（如 `antrun/`、`classes/db/desktop/`，以 `/` 结尾）被误判为文件传输行的问题，报告现在只列出实际传输的文件，与 rsync 统计 `Number of regular files transferred` 一致
- **空传输回退问题**：修复增量同步跳过所有文件（0 transferred）时，报告"实际更新的文件"回退到选中路径（含目录）的问题。现在 0 传输时显示"无文件变更"提示，不再误列选中目录或未变更文件

### 🎨 优化改进
- **SSH 优化**：新增 `-o UserKnownHostsFile=/dev/null`，消除精简 Cygwin 环境下 `Could not create directory '/home/<user>/.ssh'` 警告
- **Alt+Shift+Z 菜单定位修复**：改用 `guessBestPopupLocation(dataContext)` 定位弹窗，修复多个 IDEA 项目窗口时菜单弹到错误窗口的问题
- **changelog 统一**：统一 `build.gradle.kts` 与 `plugin.xml` 的 changelog 内容，插件市场显示完整中英文双语版本

---

## [1.0.1] - 2026-07-05

### ✨ 新功能
- **国际化支持**：新增中英文双语界面，支持在 `Settings → Tools → DeployX → Language` 中切换语言（English / 简体中文 / 跟随系统），免重启即时生效
- **DeployXBundle 国际化访问器**：基于 ResourceBundle 的统一国际化访问，精确控制 locale，避免 JVM 默认 locale 干扰
- **语言变更监听机制**：切换语言后，已打开的工具窗口、对话框会即时刷新文案，业务状态不受影响
- **右键菜单动态国际化**：Action 文案随当前语言实时刷新
- **Alt+Shift+Z 快捷操作菜单**：新增快捷键 `Alt+Shift+Z` 快速呼出 DeployX 操作菜单，无需右键即可快速执行常用操作
- **新增操作快捷键**：
  - `Alt+Shift+D` / `Alt+Shift+2`：Deploy（完整部署）
  - `Ctrl+Alt+Y`：Sync to Server（同步上传）
  - `Ctrl+Alt+Shift+P` / `Alt+Shift+3`：Quick Push（快速推送）
  - `Ctrl+Alt+T` / `Alt+Shift+4`：Open SSH Terminal（打开终端）
- **服务器编辑对话框内联测试连接**：添加/编辑服务器时可直接点击"测试连接"按钮验证 SSH 连通性，无需先保存到列表；测试在后台线程执行，不阻塞 UI
- **服务器选择对话框搜索框**：在 `Alt+Shift+Z` 菜单、"打开 SSH 终端"、"部署"、"同步到服务器"等弹出的服务器选择对话框中新增搜索框，支持按 id / 名称 / 主机 / 用户名 实时过滤（不区分大小写）
- **键盘自动聚焦搜索框**：服务器选择对话框打开后无需点击搜索框，直接输入字母/数字即可自动聚焦到搜索框并输入；仅一个匹配结果时按 `Enter` 直接确认
- **密钥文件输入框浏览按钮**：服务器编辑对话框中的密钥文件输入框支持手动输入和文件选择器两种方式
- **服务器选择对话框双击确认**：双击列表项直接确认选择，无需再点 OK 按钮
- **设置面板搜索过滤**：服务器管理 / 映射管理设置面板顶部新增搜索框，支持按 ID / 名称 / 主机 / 用户名（服务器）或名称 / 本地目录 / 服务器 / 远程目录（映射）实时过滤
- **服务器密码加密存储**：服务器密码通过 IntelliJ `PasswordSafe` 加密存储，不再以明文写入 `servers.json`；兼容旧版明文密码，首次加载时自动迁移到 PasswordSafe 并清空 JSON 中的明文

### 🎨 优化改进
- 改进了工具窗口 UI 布局，支持动态语言切换
- 优化了设置页面布局，新增独立的 Language 设置子页面
- 脚本库功能支持国际化
- **右键菜单顺序调整**：将 Deploy 移到 Sync 前面，更符合常用操作习惯
- **更新报告优化**：
  - 修复了上传目录时报告只显示目录名、不显示目录下实际更新文件的问题
  - 新增 `实际更新的文件` 区块，列出 rsync 实际传输的所有文件列表
  - 报告中显示备份文件的具体位置（配置了备份时）
  - 改进了 rsync 输出解析逻辑，精确识别文件名行，过滤进度信息、统计行和计数行
  - 支持 `lib/xxx.jar` 等嵌套目录下文件的完整显示
- **认证方式联动**：服务器编辑对话框中认证方式选择 `PASSWORD` 时仅显示密码输入框，选择 `KEY` 时仅显示密钥文件输入框，避免误填不相关字段
- **服务器选择对话框尺寸加大**：列表高度由 200 加大到 320，对话框宽度由 460 加大到 580，便于浏览较多服务器
- 服务器选择对话框在过滤后保留之前选中的服务器（按 id 恢复），减少切换焦点
- **DeployService 移除 `!!` 强制非空**：9 处 `sshConnection!!` 改为局部非空变量（forEach 内用 `?: return@forEach` 优雅退出，函数体内用 `requireNotNull`），避免后续重构时 NPE
- **抽取 AbstractDeployAction 模板方法**：DeployAction / SyncFileAction / QuickPushAction 三个 Action 共有的"获取文件 → 解析映射 → 选择服务器 → 显示工具窗口 → 执行批处理"流程抽取到 `AbstractDeployAction<T>` 基类，公共方法（`getSelectedFiles` / `showNotification` / `buildCommandAvailability`）移到 `ActionUtils`；三个 Action 从 100+ 行精简到 50 行左右
- **Script 搜索统一改为实时过滤**：`ScriptPickerDialog` / `ScriptSettingsPanel` 的搜索框从 `addActionListener`（需按 Enter）改为 `DocumentListener` 实时过滤，与服务器选择对话框行为一致
- **RemotePathChooserDialog 改用 ProgressManager**：将裸线程 `thread(start=true, isDaemon=true)` 改为 `ProgressManager.run(Task.Backgroundable, canBeCancelled=true)`，支持用户取消；新增 `@Volatile isCancelled` 标志，`dispose()` 时通知后台任务退出，避免对话框关闭后访问失效的 UI 组件
- **Manager 线程安全改造**：`ServerManager` / `MappingManager` 内部列表从 `mutableListOf` 改为 `CopyOnWriteArrayList`，读操作无锁、写操作复制数组；API 完全兼容，避免后台 Action 与 EDT 并发导致的 `ConcurrentModificationException`
- **抽取硬编码中文到 i18n**：把 `SshConnection` / `DeployService` / `ScriptManager` / `SyncService` / `UpdateReportFormatter` / `SftpTransferClient` / `RsyncWrapper` / `ScriptConfig` 等 8 个文件中约 143 处硬编码中文字符串抽取到 `DeployXBundle.properties` / `DeployXBundle_zh_CN.properties`，英文用户不再看到中文残留

### 🐛 Bug 修复
- 修复英文状态下语言设置菜单显示原始 key（如 `%settings.language.configurableName`）的问题
- 修复切换语言后已打开的工具窗口面板未刷新文案的问题
- 修复打开设置页面时的 PluginException 异常
- 修复服务器选择对话框过滤后选中索引越界的问题（原 `doOKAction` 和 `updateCommandOptions` 基于 `servers` 而非过滤后的列表）
- 删除 `SshConnection` 中遗留的 `println("[DEBUG] ...")` 和 `printStackTrace()` 调试输出，避免污染控制台和泄露内部细节
- 修复 `RemotePathChooserDialog` 关闭对话框后后台线程仍访问失效 UI 组件的潜在问题

---

## [1.0.0] - 2026-06-30

### ✨ 新功能
- **服务器管理**：支持 SSH 密码认证和密钥认证，支持连接测试、复制服务器配置、设置默认服务器
- **目录映射**：配置本地目录与远程目录的映射关系，使用自动生成的唯一 ID 管理，名称允许重复
- **智能文件传输**：
  - 支持 `AUTO` / `RSYNC_ONLY` / `SFTP_ONLY` 三种传输模式
  - 基于系统 `rsync` 进行增量传输；rsync 不可用时可自动降级为 JSch SFTP
  - 支持上传文件、目录、多文件批量上传
  - 多文件上传使用 `rsync --files-from` 合并传输，并保持相对路径
- **一键部署**：完整部署流程，按分组执行备份、上传、解压和前后置命令
- **智能备份**：部署前自动备份，支持选择文件打包备份，备份源可独立配置
- **自动解压**：上传后可按配置自动解压压缩包
- **远程命令执行**：支持上传前命令和上传后命令，可单独启用/禁用
- **SSH 终端**：一键打开远程服务器终端，协议层认证真正免密登录
- **脚本库**：可复用的远程命令片段，支持参数化、上下文插值与危险确认
- **日志与历史**：
  - 工具窗口内置"日志"Tab，实时显示 rsync/SFTP 命令与传输输出
  - 支持按服务器拆分日志子 Tab
  - 内置"历史"Tab，可查看部署记录并快速重新部署
- **Markdown 更新报告**：每次上传/部署后生成更新报告，可复制或导出，便于他人按服务器路径增量拉取文件
- **预览同步**：支持 dry-run 预览（需本机可用 rsync）

### 🎨 优化改进
- 插件正式命名为 **DeployX**（从 File Sync Tool 重命名）
- 新增插件图标，视觉设计更统一
- 工具窗口支持右侧停靠，内置操作/日志/历史三个 Tab
- 跨平台传输支持，兼容 Windows、macOS 和 Linux
- 完善的错误处理和连接重试机制
- SSH 连接失败时提供详细的异常信息

---

## [0.x.x] - Beta 版本历史

### 核心功能迭代
- 支持基本的文件同步功能
- 实现 rsync 增量传输
- 添加 SFTP fallback 支持
- 支持多文件批量上传
- 实现按服务器/映射分组的部署流程
- 添加备份和解压功能
- 添加远程命令执行功能
- 实现历史记录功能
- 添加按服务器拆分的日志功能

---

## 版本说明

版本号格式：`主版本.次版本.修订号`

- 主版本号：不兼容的 API 变更
- 次版本号：向下兼容的功能性新增
- 修订号：向下兼容的问题修正

[1.0.5]: https://github.com/zhengmingliang/DeployX/compare/v1.0.4...v1.0.5
[1.0.4]: https://github.com/zhengmingliang/DeployX/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/zhengmingliang/DeployX/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/zhengmingliang/DeployX/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/zhengmingliang/DeployX/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/zhengmingliang/DeployX/releases/tag/v1.0.0
