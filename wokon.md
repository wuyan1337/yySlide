# yySlide Workon

Last updated: 2026-07-27

## 仓库状态

该项目按 `wuyan1337/yySlide` 根 GitHub monorepo 维护；Android 主源码直接纳入
根仓库，只有 7 个上游依赖保留为递归 submodule。安全基线、跨端 CI、secret
扫描和发布流程在 2026-07-27 完成了一轮全项目审计。

## 已完成的基础工作

- Windows、Cloud、Android 和官网已分别进入安全修复与 CI 验证流程。
- 根目录已提供 Server、Website、Windows、Android 和 secret scanning 工作流。
- Cloud 使用 Node.js 24 + `node:sqlite`；生产 TOTP secret 由
  `YYSLIDE_SERVER_SECRET_FILE` 加密；旧 `db.json` 成功核验并迁移后只保留加密归档，冲突时保留原文件并停止。
- 真实 Cloud 运行数据已迁出仓库；不要在 `server/data/` 恢复或提交运行数据库。
- Cloud 默认端点和官网均为 `https://yyslide.ccwu.cc`。
- Android debug 产物放在 `releases/android/debug/`；签名 release 放在
  `releases/android/`，两者不会互相覆盖。
- 官网公开截图使用文档网段和示例账号，不应出现真实主机名、账号或内外网地址。

## 常用验证命令

```powershell
npm.cmd test
npm.cmd --prefix windows run check
npm.cmd run android:copy-audit
node android/yyslide-android/build-ffmpeg/verify-source-pins.cjs
```

Android 完整构建与测试需要 JDK 17、Android SDK/NDK 和递归 submodule；详见
根 `README.md`、`RELEASE.md` 与 CI 工作流。

## 发布前检查

- Windows 和 Android 的相关测试、lint 与构建必须通过。
- 根仓库只能跟踪源码和必要二进制依赖，不能包含数据库、日志、签名密钥或构建缓存。
- Gitleaks、依赖审计和大文件检查必须通过。
- Android release tag 必须是 `android-v<versionName>`。
- 发布流程有变更时，从 GitHub 全新 `--recurse-submodules` 克隆并复核入口命令和 CI 路径。

## 给下一位维护者

先读根 `README.md`、`SECURITY.md`、`RELEASE.md` 和本文件。改动服务迁移逻辑时，
必须保留“先验证、再归档、最后清除明文”的顺序；不能把冲突数据当成已覆盖，
也不能把 plaintext 备份留在仓库或普通数据目录。
