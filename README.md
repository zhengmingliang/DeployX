# DeployX - IntelliJ IDEA 插件

DeployX 是一个 JetBrains IntelliJ IDEA 插件，用于在 IDE 内完成本地文件/目录到远程服务器的快速同步、部署、备份、解压和远程命令执行。

## 功能特性

- **服务器管理**
  - 支持 SSH 密码认证和密钥认证
  - 支持连接测试、复制服务器配置、设置默认服务器
  - 添加/编辑服务器对话框内联"测试连接"按钮，配置完成后可直接验证连通性，无需先保存到列表
  - 认证方式联动：选择 `PASSWORD` 时仅显示密码输入框，选择 `KEY` 时仅显示密钥文件输入框
  - 密钥文件输入框支持手动输入路径和文件选择器两种方式

- **目录映射**
  - 配置本地目录与远程目录的映射关系
  - 映射使用自动生成的唯一 ID 管理，名称允许重复
  - 支持复制映射配置，便于快速新增相似配置
  - 兼容旧版本无 ID 的历史映射数据

- **文件同步与部署**
  - 支持 `AUTO` / `RSYNC_ONLY` / `SFTP_ONLY` 三种传输模式
  - 基于系统 `rsync` 进行增量传输；rsync 不可用时可自动降级为 JSch SFTP
  - 支持上传文件、目录、多文件批量上传
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
  - 丰富的快捷键支持：
    - `Alt+Shift+D` / `Alt+Shift+2`：Deploy（完整部署）
    - `Ctrl+Alt+Y`：Sync to Server（同步上传）
    - `Ctrl+Alt+Shift+P` / `Alt+Shift+3`：Quick Push（快速推送）
    - `Ctrl+Alt+T` / `Alt+Shift+4`：Open SSH Terminal（打开终端）
  - 服务器选择对话框支持实时搜索过滤（按 id / 名称 / 主机 / 用户名匹配），适配较多服务器场景；列表尺寸加大，对话框打开后键盘输入字母/数字会自动聚焦到搜索框

- **国际化支持**
  - 支持中英文双语界面
  - 可在 `Settings → Tools → DeployX → Language` 中切换语言（English / 简体中文 / 跟随系统）
  - 免重启切换，所有 UI 文案即时刷新

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
build/distributions/DeployX-1.0.1.zip
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
build/distributions/DeployX-1.0.1.zip
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

- **操作**：手动输入本地文件、远程目录并执行预览/部署/快速推送
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
├── servers.json
├── mappings.json
└── history.json
```

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
