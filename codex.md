# yySlide Codex Project Notes

这个文件给后续没有历史记忆的 AI 使用。开始干活前先读这里和根目录的 `wokon.md`。

## 项目是什么

yySlide 是一个跨端 SSH/SFTP 客户端，包含：

- `windows/`: Windows 桌面端，Electron + React + TypeScript + Vite。
- `android/yyslide-android/`: Android 客户端，基于 GlassHaven/Haven 改造，UI 和同步改为 yySlide 方向。包名是 `com.yyslide.android`，但很多 Kotlin namespace 仍是 `sh.haven.*`。
- `server/`: yySlide Cloud 登录、2FA、恢复词、加密 vault 存储和 SSE 推送服务。
- `scripts/`: 根目录辅助脚本。
- `releases/`: 构建产物目录。Windows 和 Android 包都应该输出到这里。

根目录是 `wuyan1337/yySlide` 的主 Git monorepo。Android 主源码直接由根仓库
跟踪，不要把 `android/yyslide-android` 当成一个顶层 gitlink。Android 内部只有
以下 7 个上游依赖保留为 submodule：`build-proot/proot-termux`、`et-kotlin`、
`mosh-kotlin`、`reticulum-kt`、`rnsh-kt`、`termlib`、`wayland-android`。
克隆和 CI 都要初始化递归 submodule。不要用 `git reset --hard` 等破坏性命令。

## 常用命令

```powershell
npm.cmd run windows:build
npm.cmd run windows:dist
npm.cmd run android:dist
npm.cmd test
npm.cmd run release:all
```

Windows 构建输出：

- `releases/windows/win-unpacked/yySlide.exe`
- `releases/windows/yySlide-Setup-0.1.0-x64.exe`

Android 构建输出：

- Debug：`releases/android/debug/yyslide-5.51.0-x64-debug.apk`
- Debug：`releases/android/debug/yyslide-5.51.0-arm64-debug.apk`
- 签名 release：`releases/android/yyslide-5.51.0-x64-release.apk`
- 签名 release：`releases/android/yyslide-5.51.0-arm64-release.apk`

如果模拟器在线，可以安装：

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb install -r releases\android\debug\yyslide-5.51.0-x64-debug.apk
```

## Windows 端关键文件

- `windows/src/App.tsx`: 主要 UI、连接管理、分类管理、同步刷新入口。
- `windows/src/types.ts`: preload 暴露给前端的 API 类型。
- `windows/electron/main.cjs`: Electron 主进程、连接存储、账号登录、云同步、SSE、SSH/SFTP IPC。
- `windows/electron/preload.cjs`: `window.yySlide` API 暴露。
- `windows/package.json`: Electron Builder 配置，输出到 `../releases/windows`。

Windows 本地连接数据在 Electron userData 下，不在 repo 里。分类现在由主进程保存到 `connection-groups.json`，不要再只依赖 `localStorage`。

## Android 端关键文件

- `android/yyslide-android/app/build.gradle.kts`: appId、版本、ABI 分包名。
- `android/yyslide-android/app/src/main/AndroidManifest.xml`: app 入口、图标、label。
- `android/yyslide-android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`: Android 8+ adaptive icon。
- `android/yyslide-android/app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml`: Android 13+ adaptive icon 和 themed icon。
- `android/yyslide-android/app/src/main/res/drawable-nodpi/yyslide_logo.png`: yySlide logo PNG，带 Alpha 通道。
- `android/yyslide-android/feature/connections/src/main/kotlin/sh/haven/feature/connections/ConnectionsViewModel.kt`: 连接和分类操作，保存后触发 yySlide Cloud 同步。
- `android/yyslide-android/feature/settings/src/main/kotlin/sh/haven/feature/settings/YySlideCloudRepository.kt`: Android yySlide Cloud 登录、拉取、导入、导出、SSE 监听。
- `android/yyslide-android/feature/settings/src/main/kotlin/sh/haven/feature/settings/SettingsScreen.kt`: yySlide 设置 UI。

Android 默认云服务器：

```text
https://yyslide.ccwu.cc
```

Android 当前维护 12 种语言：英语、中文、阿拉伯语、孟加拉语、德语、西班牙语、
法语、印地语、日语、韩语、葡萄牙语和俄语。

- `android/yyslide-android/app/src/main/res/xml/locales_config.xml` 声明全部 12 种语言。
- 新增用户可见文案必须同步补齐对应模块的 `values-*` 资源；提交前运行根目录的 `npm run android:copy-audit`。
- yySlide Cloud 登录、同步结果、设置区和登录弹窗都应使用 string resource，不要在 Kotlin 里写死中文或英文。
- 当前导航使用 `SettingsScreen`；语言选择支持跟随系统或上述 12 种语言，默认空 app locale 即跟随系统。
- `SettingsScreen` 里可见设置文案必须使用 string resource。不要直接显示仅含英文的 enum label；使用页面的本地化映射。

## 服务端关键文件

- `server/yycloud.cjs`: Node.js 24 服务端，使用 `node:sqlite`，提供注册、登录、2FA、恢复码、vault 保存、SSE 推送。
- `server/DEPLOY.md`: 服务端部署说明。
- 服务端运行数据必须放在仓库外的受限数据目录；`server/data/` 不再保存真实运行数据，也不能提交。

服务端不解析客户端 vault 内容。Windows/Android 改 vault schema 时，一般不需要改服务端，只要加密 payload 仍能保存。

生产环境关键环境变量：

- `YYSLIDE_SERVER_SECRET_FILE`: 生产环境首选，指向权限受限的随机密钥文件。
- `YYSLIDE_SERVER_SECRET`: 仅在无法使用密钥文件时作为互斥的内联替代。
- `YYSLIDE_CLOUD_HOST=127.0.0.1`
- `YYSLIDE_CLOUD_PORT=8787`
- `YYSLIDE_CLOUD_DATA=/var/lib/yyslide-cloud`
- `YYSLIDE_TRUST_PROXY=1`

## 云同步模型

客户端数据放进加密 vault，服务端只存密文。当前 vault schema 是 `version: 3`。

核心字段：

- `connections`: SSH 连接列表。
- `deletedConnections`: 删除连接墓碑，避免旧端把已删除连接同步回来。
- `groups`: 连接分类列表，包含空分类。
- `deletedGroups`: 删除分类墓碑，避免空分类删除后又从旧快照恢复。

重要逻辑：

- Windows 在 `windows/electron/main.cjs` 里导入/导出 vault。
- Android 在 `YySlideCloudRepository.kt` 里导入/导出 vault。
- 删除分类必须记录 `deletedGroups`，否则空分类没有连接引用，无法表达“删除”。
- 实时同步走服务端 SSE `vault.updated`，客户端收到其它端更新后拉取。

## Android 图标规则

yySlide Android 图标使用透明 PNG：

- Logo: `app/src/main/res/drawable-nodpi/yyslide_logo.png`
- Adaptive icon foreground: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Adaptive icon background: v26/v33 XML 里直接使用 `@android:color/transparent`
- Manifest:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher"
```

注意：部分 Android Launcher 会强制套圆角、主题图标蒙版或背景色，这不是 PNG Alpha 问题。

## 工作习惯

- 每次开始任务先读 `codex.md` 和 `wokon.md`。
- 每次完成有意义的代码/配置/构建动作后，更新 `wokon.md`。
- 项目结构、同步协议、构建命令、部署规则发生长期变化时，更新 `codex.md`。
- 文件编辑优先用 `apply_patch`。
- 搜索优先用 `rg`。
- 构建后如果有 APK/安装包，确认产物在 `releases/`。
