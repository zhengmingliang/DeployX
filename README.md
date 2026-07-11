# DeployX - IntelliJ IDEA 插件

DeployX 是一个 JetBrains IntelliJ IDEA 插件，用于在 IDE 内完成本地文件/目录到远程服务器的快速同步、部署、备份、解压和远程命令执行。

## 功能特性

- **服务器管理**
  - 支持 SSH 密码认证和密钥认证
  - 支持连接测试、复制服务器配置、设置默认服务器
  - 添加/编辑服务器对话框内联"测试连接"按钮，配置完成后可直接验证连通性，无需先保存到列表
  - 认证方式联动：选择 `PASSWORD` 时仅显示密码输入框，选择 `KEY` 时仅显示密钥文件输入框
  - 密钥文件输入框支持手动输入路径和文件选择器两种方式
  - 服务器密码通过 IntelliJ `PasswordSafe` 加密存储，不写入 `servers.json` 明文；兼容旧版明文密码，首次加载自动迁移
  - 服务器列表表格支持实时搜索过滤（按 ID / 名称 / 主机 / 用户名）

- **目录映射**
  - 配置本地目录与远程目录的映射关系
  - 映射使用自动生成的唯一 ID 管理，名称允许重复
  - 支持复制映射配置，便于快速新增相似配置
  - 兼容旧版本无 ID 的历史映射数据
  - 映射列表表格支持实时搜索过滤（按名称 / 本地目录 / 服务器 / 远程目录）

- **文件同步与部署**
  - 支持 `AUTO` / `RSYNC_ONLY` / `SFTP_ONLY` 三种传输模式
  - 基于系统 `rsync` 进行增量传输；rsync 不可用时可自动降级为 JSch SFTP
  - 支持上传文件、目录、多文件批量上传
  - 支持**双向同步（从服务器拉取）**：本地与远程文件增量双向同步，自动合并差异；可「从服务器拉取」（Pull from Server）将远程与本地不同的文件拉取到本地，rsync 模式按大小/修改时间只传输差异文件，SFTP 降级时递归下载整个远程目录
  - 多文件上传使用 `rsync --files-from` 合并传输，并保持相对路径
  - 上传目录时会上传目录本身，而不是仅上传目录内容
  - `Sync to Server` / `Quick Push to Server` 为 upload-only，默认不执行备份/解压/前后置命令
  - `Deploy` 为完整部署流程，按分组执行备份、上传、解压和前后置命令
  - 支持预览同步（dry-run，需本机可用 rsync）
  - 支持右键菜单和工具窗口两种操作方式

- **备份与解压**
  - 目录映射中可独立配置是否启用部署前备份
  - 目录映射中可独立配置是否启用上传后解压
  - 支持配置备份目录和备份源
  - 备份源可用于“上传压缩包，但实际备份解压后的同名目录”等场景
  - 压缩包备份会追加日期后缀
  - 目录或非压缩文件备份会生成 tar.gz 包

- **远程命令执行**
  - 支持上传前命令和上传后命令
  - 目录映射中可单独启用/禁用上传前、上传后命令
  - `Sync` / `Quick Push` 默认不执行命令，可在服务器选择弹窗中手动勾选执行
  - `Deploy` 默认按目录映射配置执行启用的命令
  - 支持自动切换到远程目录后执行命令
  - SSH 命令通过 shell 执行，支持服务器侧常用脚本/环境配置

- **日志、历史与更新报告**
  - 工具窗口内置“日志”Tab，实时显示 rsync/SFTP 命令与传输输出
  - 日志 Tab 内支持 `全部` 与按服务器拆分的子 Tab，类似 IDEA File Transfer 视图
  - 日志中会隐藏 sshpass 密码
  - 工具窗口内置“历史”Tab，可查看部署记录并快速重新部署
  - 每次上传/部署后生成 Markdown 更新报告，可复制或导出，便于他人按服务器路径增量拉取文件
  - **更新报告优化**（v1.0.1）：上传目录时显示目录下实际更新的具体文件列表，并显示备份文件位置

- **快捷操作菜单**
  - 支持 `Alt+Shift+Z` 快捷呼出 DeployX 操作菜单，无需右键即可快速执行常用操作
  - `Alt+Shift+Z` 快捷菜单包含：Deploy（完整部署）、Sync to Server（同步上传）、Quick Push（快速推送）、Preview Sync（预览同步）、**Pull from Server（从服务器拉取）**、Open SSH Terminal（打开终端）
  - 丰富的快捷键支持：
    - `Alt+Shift+D` / `Alt+Shift+2`：Deploy（完整部署）
    - `Ctrl+Alt+Y`：Sync to Server（同步上传）
    - `Ctrl+Alt+Shift+P` / `Alt+Shift+3`：Quick Push（快速推送）
    - `Ctrl+Alt+T` / `Alt+Shift+4`：Open SSH Terminal（打开终端）
  - 服务器选择对话框支持实时搜索过滤（按 id / 名称 / 主机 / 用户名匹配），适配较多服务器场景；列表尺寸加大，对话框打开后键盘输入字母/数字会自动聚焦到搜索框
  - 服务器选择对话框支持双击列表项直接确认选择

- **国际化支持**
  - 支持中英文双语界面
  - 可在 `Settings → Tools → DeployX → Language` 中切换语言（English / 简体中文 / 跟随系统）
  - 免重启切换，所有 UI 文案即时刷新
  - 日志、错误消息、Markdown 更新报告等所有面向用户的文案均已国际化，英文用户不会看到中文残留

- **远程文件浏览器（🆕 v1.0.5）**
  - 在 IDE 侧边栏（右侧停靠）以树形结构浏览远程服务器文件系统，非模态窗口不影响本地项目编辑
  - **查看**：懒加载目录树，显示文件大小/修改时间/图标；切换服务器、路径导航（支持手动输入路径历史记忆，每服务器最多 20 条，可跳转/删除单条/清空）、新建/删除目录、刷新
  - **编辑**：双击远程文本文件直接在 IDE 主编辑器中打开（写入本地临时文件确保保存管线正常触发），完整语法高亮；`Ctrl+S` 自动将修改写回远程服务器；超过 5MB 文件拒绝打开并提示下载
  - **在终端中打开**：右键文件或目录选择「在终端中打开」，自动打开对应服务器的 SSH 终端并 `cd` 到所在目录
  - **上传**：从项目视图或系统文件管理器拖拽本地文件/目录到浏览器树，自动上传到目标远程目录并在完成后刷新目录
  - **下载**：右键下载文件/目录到本地（带进度）；或从浏览器树拖拽文件到项目视图直接下载
  - **自动映射与路径记忆**：首次打开按当前项目路径匹配映射自动定位到对应远程目录；每台服务器上次打开目录被记忆
  - 持久化 SFTP 通道复用 + 自动重连 + SFTP 异常自动重试，浏览延迟低、连接稳定
  - 入口：右键菜单 `DeployX → 浏览远程文件`、`Alt+Shift+Z` 快捷菜单、DeployX 工具窗口「操作」Tab 服务器旁的文件夹按钮
  - 完整中英文双语支持

## 环境要求

- IntelliJ IDEA 2022.3+（since-build: `223`）
- JDK 17+
- 本机建议安装 `rsync` 以获得增量传输能力
- 如果使用 rsync + SSH 密码认证，建议安装 `sshpass`
- 未安装 `rsync` 或密码认证缺少 `sshpass` 时，默认 `AUTO` 模式会降级为 SFTP 上传

Linux 环境安装示例：

```bash
sudo apt install rsync sshpass
```

## 跨平台传输模式

插件支持三种传输模式，可在 `Settings → Tools → DeployX → rsync 配置` 中设置：

| 模式 | 行为 |
| --- | --- |
| AUTO | 默认模式，优先使用 rsync；rsync 不可用或密码认证缺少 sshpass 时，自动降级为 SFTP |
| RSYNC_ONLY | 只使用 rsync，不降级；缺少 rsync/sshpass 时会失败并提示 |
| SFTP_ONLY | 只使用 JSch SFTP，不依赖本机 rsync/ssh/sshpass |

### Windows

Windows 默认通常没有 `rsync` 和 `sshpass`。建议使用 `AUTO` 或 `SFTP_ONLY`。如果需要 rsync 增量传输，可安装 MSYS2、cwRsync 或 Git Bash 中的 rsync，并在设置中配置 `rsync.exe` 路径。

### macOS

macOS 通常没有 `sshpass`。如果使用密码认证，`AUTO` 模式会自动降级为 SFTP。若需要新版 rsync，可使用：

```bash
brew install rsync
```

### SFTP fallback 限制

SFTP fallback 可跨平台上传文件/目录，但不具备 rsync 的增量算法、精确 dry-run 和完整 rsync exclude 语义。预览功能仍需要 rsync。

## 项目结构

```text
DeployX/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── src/main/kotlin/com/alianga/idea/deploy/
│   ├── action/          # 右键菜单和快捷键 Action
│   ├── config/          # 插件设置和 JSON 配置管理
│   ├── model/           # 数据模型
│   ├── service/         # 服务器、映射、同步、部署、历史服务
│   ├── ssh/             # SSH 与 rsync 封装
│   └── ui/              # 工具窗口、设置页、对话框
└── src/main/resources/
    ├── META-INF/plugin.xml
    └── icons/
```

## 构建与运行

### 编译

```bash
./gradlew compileKotlin
```

### 构建插件包

```bash
./gradlew buildPlugin
```

生成的插件包位于：

```text
build/distributions/DeployX-1.0.4.zip
```

### 在沙箱 IDE 中运行

```bash
./gradlew runIde
```

> 当前 `build.gradle.kts` 使用本地 IntelliJ IDEA Ultimate 路径作为 SDK：
>
> ```kotlin
> localPath.set("/home/zml/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate")
> ```
>
> 如果换机器开发，需要根据本机安装路径调整，或改为下载指定 IntelliJ Platform 版本。

## 安装插件

1. 打开 IntelliJ IDEA
2. 进入 `Settings / Preferences → Plugins`
3. 点击齿轮按钮，选择 `Install Plugin from Disk...`
4. 选择：

```text
build/distributions/DeployX-1.0.4.zip
```

5. 重启 IDE

## 使用说明

### 1. 配置服务器

进入：

```text
Settings / Preferences → Tools → DeployX → 服务器管理
```

可新增、编辑、复制、删除服务器，并测试 SSH 连接。

服务器添加/编辑对话框说明：

- **认证方式**：选择 `PASSWORD` 时仅显示密码输入框；选择 `KEY` 时仅显示密钥文件输入框，未使用的字段会自动隐藏
- **密钥文件**：既可手动输入路径，也可点击右侧浏览按钮通过文件选择器选取
- **测试连接**：对话框内嵌"测试连接"按钮，填完配置后可直接验证 SSH 连通性，无需先保存到服务器列表；测试过程在后台执行，不会阻塞 UI
- 列表工具栏同样提供"测试连接"按钮，用于验证已保存的服务器配置
- **密码安全存储**：服务器密码通过 IntelliJ `PasswordSafe` 加密保存，不写入 `servers.json` 明文；旧版本中的明文密码会在首次加载时自动迁移到 PasswordSafe 并清空明文
- 服务器列表顶部提供**搜索框**，支持按 ID / 名称 / 主机 / 用户名实时过滤

### 2. 配置目录映射

进入：

```text
Settings / Preferences → Tools → DeployX → 目录映射
```

主要字段说明：

| 字段 | 说明 |
| --- | --- |
| 名称 | 映射显示名称，允许重复 |
| 本地目录 | 本地文件或目录的匹配路径 |
| 目标服务器 | 远程服务器配置 |
| 远程目录 | 上传目标目录 |
| 启用部署前备份 | 是否在上传前备份远程文件/目录 |
| 备份目录 | 备份文件保存目录 |
| 备份源 | 可选，指定实际需要备份的远程路径 |
| 启用上传后解压 | 是否在上传完成后执行解压 |
| 解压目标 | 解压目标目录 |
| 上传前命令 | 上传前执行的远程 shell 命令 |
| 启用上传前命令 | 是否执行上传前命令 |
| 上传后命令 | 上传后执行的远程 shell 命令 |
| 启用上传后命令 | 是否执行上传后命令 |
| 命令自动切换到远程目录 | 勾选后命令会以 `cd <远程目录> && ...` 形式执行 |

### 3. 通过工具窗口操作

打开右侧工具窗口：

```text
DeployX
```

包含三个 Tab：

- **操作**：手动输入本地文件、远程目录并执行预览/部署/快速推送；也可通过「从服务器拉取」将远程差异文件拉取到本地
- **日志**：实时查看 rsync/SFTP 命令和上传进度，内部包含 `全部` 与按服务器拆分的日志子 Tab
- **历史**：查看历史记录并快速重新部署

工具栏提供：

- **Copy Report**：复制最近一次上传/部署的更新报告
- **Export Report**：将最近一次更新报告导出为 Markdown 文件

### 4. 通过右键菜单操作

在 Project 视图或编辑器中右键文件/目录：

```text
DeployX → Sync to Server
DeployX → Quick Push to Server
DeployX → Deploy (Backup + Upload + Unzip)
DeployX → Preview Sync
DeployX → Pull from Server (Download from Server)
```

当同一路径匹配多个服务器映射时，会弹出服务器选择对话框。

- `Sync to Server` / `Quick Push to Server`：只上传，不执行备份/解压/命令；如需执行上传前后命令，可在弹窗中手动勾选
- `Deploy`：完整部署，按目录映射执行备份、上传、解压、上传前/后命令
- 多文件上传/部署会按服务器和映射分组，使用 `--files-from` 合并传输

服务器选择对话框说明：

- 列表上方提供**搜索框**，支持按 id / 名称 / 主机 / 用户名 实时过滤（不区分大小写）
- 对话框尺寸已加大，便于浏览较多服务器
- **键盘自动聚焦**：对话框打开后无需点击搜索框，直接输入字母/数字即可自动聚焦到搜索框并输入；若仅有一个匹配结果，按 `Enter` 即可直接确认选择
- 列表选中后按 `Enter` 确认，按 `Esc` 取消

## 快捷键

默认快捷键：

| 操作 | 快捷键 |
| --- | --- |
| Sync to Server | `Ctrl + Alt + Y` |
| Quick Push to Server | `Ctrl + Alt + Shift + P` |

可在 IntelliJ IDEA 的 `Settings → Keymap` 中搜索 `DeployX` 后自行修改。

## 配置文件位置

插件配置会写入用户目录下：

```text
~/.deploy-x/
├── servers.json   # 服务器配置（不含密码明文）
├── mappings.json
└── history.json
```

服务器密码通过 IntelliJ `PasswordSafe` 加密存储，保存在 IDE 的凭据存储中（具体位置取决于 IDE 的 PasswordSafe 配置：默认 keyring、KeePassXC 或系统 keychain），不会出现在 `servers.json` 中。

IDE 持久化设置会写入 IntelliJ 配置目录中的 `FileSyncTool.xml`。

## 日志查看

- 插件工具窗口 → **日志** Tab：查看实时同步日志
- IntelliJ IDEA 主日志：`Help → Show Log in Explorer/Finder`
- Debug 日志设置：`Help → Diagnostic Tools → Debug Log Settings`，添加：

```text
#com.alianga.idea.deploy
```

## 开发说明

### 主要依赖

- Kotlin `2.3.10`
- IntelliJ Gradle Plugin `1.16.0`
- JSch `0.1.55`
- Gson `2.10.1`

### 常用命令

```bash
# 清理并打包
./gradlew clean buildPlugin

# 仅编译 Kotlin
./gradlew compileKotlin

# 运行沙箱 IDE
./gradlew runIde
```

## 注意事项

1. 密码认证依赖 `sshpass`，如果未安装，rsync 密码认证可能失败。
2. 远程命令执行依赖服务器 shell 环境，若使用别名或自定义函数，请确保服务器登录 shell 能加载相关配置。
3. 备份源如果不填写，插件会根据上传文件/目录自动推断远程备份对象。
4. 上传目录时不会自动追加尾部 `/`，因此会上传目录本身。
5. 服务器密码通过 IntelliJ `PasswordSafe` 加密存储，迁移到其他机器时需重新配置密码（`servers.json` 中不含密码）。
6. 旧版本升级后，`servers.json` 中的明文密码会在首次启动时自动迁移到 PasswordSafe，原明文会被清空。

## 更新日志

### v1.0.4

- **双向同步（从服务器拉取）**：新增本地与远程文件的双向增量同步，自动合并差异。右键或 `Alt+Shift+Z` 快捷菜单的「从服务器拉取」，可将远程与本地不同的文件拉取到本地；rsync 按大小/修改时间只传输差异文件，SFTP 降级时递归下载整个远程目录
- **rsync 拉取方向反转修复**：「从服务器拉取」rsync 模式原误把本地当源、远程当目标（实为上传），现按方向动态交换源/目标
- **拉取日志优化**：下载分组改用独立「从服务器拉取分组」标题，明确打印方向、远程源目录与本地目标目录
- **Alt+Shift+Z 菜单补充「从服务器拉取」**：快捷菜单已加入该入口
- **rsync 拉取目录/映射根修复**：选中目录或映射根（相对路径为空）时原传输 0 文件，现空项走整目录递归拉取，非空项仍走 `--files-from`
- **历史 Tab 空白修复**：切换至历史 Tab 时自动重新加载最新记录，列表为空时显示「暂无历史记录」占位

> 完整更新日志见 [CHANGELOG.md](CHANGELOG.md)。
