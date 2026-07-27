# yySlide

yySlide 是一个面向 Windows 的 SSH / SFTP 桌面客户端 MVP，界面布局参考 FinalShell，工程实现参考 electerm、Tabby、WindTerm 和 xterm.js 的方向。

## 当前功能

- SSH 连接标签页
- xterm.js 终端渲染
- 连接信息保存
- 密码和密钥口令使用 Electron safeStorage 加密
- 私钥路径保存
- 左侧 Linux 主机实时监控
- CPU、内存、交换分区、负载、网络速率、进程排行、磁盘列表
- 底部 SFTP 文件面板
- 远端目录浏览、上传、下载、新建文件夹、重命名、删除
- 终端背景色、文字色、强调色和毛玻璃开关

## 开发运行

```powershell
npm.cmd run dev
```

## 运行已构建版本

```powershell
npm.cmd run build
npm.cmd run start
```

## 打包 Windows 程序

```powershell
npm.cmd run dist
```

打包产物会输出到仓库根目录的 `releases/windows/`。

## 依赖审计说明

CI 使用 Node 24 执行完整检查、Windows 目录打包冒烟，并对实际随应用发布的 production 依赖运行 `npm audit --omit=dev`；当前结果为 0 个漏洞。

完整开发依赖审计目前会报告 16 个 high，均来自 `electron-builder` 的旧版 `glob` / `minimatch` / `brace-expansion` 构建链，不会打入应用运行时。不要用全局 `brace-expansion@5` override 消除这组报告：旧版 `minimatch` 依赖 CommonJS callable API，这样覆盖会破坏打包工具。待 `electron-builder` 上游提供兼容升级后再移除该例外。

## 参考方向

- electerm: Electron 桌面客户端和文件管理体验
- Tabby: 现代终端应用结构
- WindTerm: SSH/SFTP/Pane 类客户端能力边界
- xterm.js: 终端渲染
- ssh2 / ssh2-sftp-client: SSH 与 SFTP 协议层思路
