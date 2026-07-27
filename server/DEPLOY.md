# yySlide Cloud 部署指南

这份文档只针对服务端 `server/yycloud.cjs`。桌面客户端默认连接：

```text
https://yyslide.ccwu.cc
```

## 前置要求

- Ubuntu 22.04/24.04
- Node.js 24 或更高版本
- SQLite 3 命令行工具（用于在线备份和升级前检查）
- 一个域名，例如 `yyslide.ccwu.cc`
- Caddy 或 nginx 做 HTTPS 反向代理

检查 Node 版本：

```bash
node -v
```

```bash
sudo apt-get update
sudo apt-get install -y sqlite3
sqlite3 --version
```

## 创建运行用户和目录

```bash
sudo useradd --system --home /var/lib/yyslide-cloud --shell /usr/sbin/nologin yyslide
sudo install -d -o root -g root -m 0755 /opt/yyslide
sudo install -d -o yyslide -g yyslide -m 0700 /var/lib/yyslide-cloud
sudo install -d -o root -g yyslide -m 0750 /etc/yyslide-cloud
```

把项目代码放到：

```text
/opt/yyslide
```

服务端主文件路径应是：

```text
/opt/yyslide/server/yycloud.cjs
```

## 生成服务端密钥

生产环境必须设置服务端密钥。推荐通过
`YYSLIDE_SERVER_SECRET_FILE` 读取权限为 0600 的文件，不要把密钥直接写进
systemd unit、仓库、`.env` 或命令历史。它用于加密数据库里的 2FA secret
和旧 JSON 加密归档；丢失或随意更换后，已有数据将无法解密。

```bash
sudo sh -c 'umask 077; openssl rand -base64 48 > /etc/yyslide-cloud/server-secret'
sudo chown yyslide:yyslide /etc/yyslide-cloud/server-secret
sudo chmod 0600 /etc/yyslide-cloud/server-secret
sudo -u yyslide test -r /etc/yyslide-cloud/server-secret
```

把同一个值另存到密码管理器或离线密钥备份中。不要只备份数据库。

## systemd 服务

创建服务文件：

```bash
sudo nano /etc/systemd/system/yyslide-cloud.service
```

内容示例：

```ini
[Unit]
Description=yySlide Cloud
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=yyslide
Group=yyslide
WorkingDirectory=/opt/yyslide
Environment=NODE_ENV=production
Environment=YYSLIDE_CLOUD_HOST=127.0.0.1
Environment=YYSLIDE_CLOUD_PORT=8787
Environment=YYSLIDE_CLOUD_DATA=/var/lib/yyslide-cloud
Environment=YYSLIDE_CLOUD_DB=/var/lib/yyslide-cloud/yycloud.sqlite
Environment=YYSLIDE_SERVER_SECRET_FILE=/etc/yyslide-cloud/server-secret
Environment=YYSLIDE_SESSION_DAYS=30
Environment=YYSLIDE_TRUST_PROXY=1
Environment=YYSLIDE_MAX_USERS=10000
Environment=YYSLIDE_MAX_PENDING_REGISTRATIONS=5000
Environment=YYSLIDE_MAX_VAULT_BYTES=2097152
Environment=YYSLIDE_MAX_SESSIONS_PER_USER=20
Environment=YYSLIDE_MAX_SSE_PER_USER=5
Environment=YYSLIDE_MAX_SSE_PER_IP=20
Environment=YYSLIDE_MAX_SSE_TOTAL=1000
Environment=YYSLIDE_MAX_SSE_BUFFER_BYTES=65536
Environment=YYSLIDE_MAX_RATE_BUCKETS=10000
Environment=YYSLIDE_SCRYPT_CONCURRENCY=2
Environment=YYSLIDE_SCRYPT_QUEUE_LIMIT=32
ExecStart=/usr/bin/node /opt/yyslide/server/yycloud.cjs
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
CapabilityBoundingSet=
ReadWritePaths=/var/lib/yyslide-cloud

[Install]
WantedBy=multi-user.target
```

代码目录应由 `root:root` 拥有并且服务账号不可写：

```bash
sudo chown -R root:root /opt/yyslide
sudo chmod -R go-w /opt/yyslide
```

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now yyslide-cloud
sudo systemctl status yyslide-cloud
```

看日志：

```bash
journalctl -u yyslide-cloud -f
```

## Caddy HTTPS 反代

安装 Caddy 后写入：

```bash
sudo nano /etc/caddy/Caddyfile
```

示例：

```caddyfile
yyslide.ccwu.cc {
  encode zstd gzip

  handle /health {
    reverse_proxy 127.0.0.1:8787
  }

  handle /v1/* {
    reverse_proxy 127.0.0.1:8787
  }

  handle {
    root * /opt/yyslide/website
    header {
      Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'; upgrade-insecure-requests"
      X-Content-Type-Options "nosniff"
      Referrer-Policy "strict-origin-when-cross-origin"
      Permissions-Policy "camera=(), microphone=(), geolocation=()"
    }
    file_server
  }
}
```

这个配置只把 Cloud API 路径转发到 Node.js，并从
`/opt/yyslide/website` 提供静态官网。不要把全部路径都反代到 8787，
否则官网首页会落到 API 的 404 响应。

重载：

```bash
sudo systemctl reload caddy
```

健康检查：

```bash
curl -fsS https://yyslide.ccwu.cc/health
```

正常返回类似：

```json
{"ok":true,"now":"2026-05-24T00:00:00.000Z"}
```

## 防火墙

只暴露 HTTP/HTTPS，不要把 `8787` 直接暴露到公网。

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

确认服务只监听本机：

```bash
ss -ltnp | grep 8787
```

应该看到 `127.0.0.1:8787`。

## 数据和备份

默认数据目录：

```text
/var/lib/yyslide-cloud
```

核心文件：

```text
/var/lib/yyslide-cloud/yycloud.sqlite
```

备份时先让 SQLite 做在线备份：

```bash
sudo -u yyslide sqlite3 /var/lib/yyslide-cloud/yycloud.sqlite ".backup '/var/lib/yyslide-cloud/backup-$(date +%F-%H%M%S).sqlite'"
```

至少同时备份：

- `yycloud.sqlite`
- `/etc/yyslide-cloud/server-secret`
- `db.json.migrated-*.enc`（如果从旧 JSON 迁移过）

缺少对应的服务端密钥时，数据库里的 2FA secret 和旧 JSON 加密归档都无法解密。

不要把 `/var/lib/yyslide-cloud`、`server/data/`、SQLite 数据库、旧版 `db.json`、日志或备份文件放进公开发布包或源码仓库。

## 旧数据的一次性安全迁移

迁移命令只应在服务停止、已经完成加密备份的情况下运行。迁移前的
SQLite 备份可能仍含 plaintext TOTP，不能留在数据目录、普通文件系统快照
或未加密的异地副本中。先用组织的加密备份系统或离线加密卷保存并验证它；
如果当前没有这样的备份介质，应停止迁移流程，而不是生成一个长期存在的
`pre-migration-*.sqlite` 明文文件。

迁移会：

- 把旧 `db.json` 导入 SQLite，但不会覆盖非空且无法核对的 SQLite 数据库；
  遇到冲突会保留原文件并退出，必须人工核对；
- 只对 plaintext TOTP 字段执行定点 `UPDATE`；
- 用服务端密钥将旧 JSON 原子写成 `db.json.migrated-*.enc`，确认落盘后清除明文源文件；
- 在独占迁移模式执行 WAL checkpoint、`secure_delete` 和 `VACUUM`；
- 输出记录数量以及 `plaintextTotpValues`，不会输出 secret。

```bash
sudo systemctl stop yyslide-cloud
# 在这里完成并验证加密/离线的迁移前备份；不要把 plaintext SQLite
# 备份留在 /var/lib/yyslide-cloud。
sudo -u yyslide env \
  NODE_ENV=production \
  YYSLIDE_CLOUD_DATA=/var/lib/yyslide-cloud \
  YYSLIDE_CLOUD_DB=/var/lib/yyslide-cloud/yycloud.sqlite \
  YYSLIDE_SERVER_SECRET_FILE=/etc/yyslide-cloud/server-secret \
  /usr/bin/node /opt/yyslide/server/yycloud.cjs --migrate-only
```

只有输出中的 `plaintextTotpValues` 为 `0`，加密归档存在，并且迁移前的
加密/离线备份已验证可恢复时，才启动服务：

```bash
sudo -u yyslide test ! -e /var/lib/yyslide-cloud/db.json
sudo -u yyslide ls -l /var/lib/yyslide-cloud/db.json.migrated-*.enc
sudo systemctl start yyslide-cloud
```

需要检查旧归档时，恢复到单独的临时目录，不要覆盖在线数据：

```bash
sudo -u yyslide install -d -m 0700 /var/lib/yyslide-cloud/recovery
sudo -u yyslide env \
  YYSLIDE_SERVER_SECRET_FILE=/etc/yyslide-cloud/server-secret \
  /usr/bin/node /opt/yyslide/scripts/decrypt-legacy-archive.cjs \
  /var/lib/yyslide-cloud/db.json.migrated-时间戳.enc \
  /var/lib/yyslide-cloud/recovery/db.json
```

恢复出的 JSON 是敏感明文；检查完成后按组织的数据销毁流程处理。
应用层覆盖、`secure_delete` 和 `VACUUM` 不能清除已经存在的磁盘快照、
云盘快照或 SSD 控制器保留块；这些副本必须单独删除或按存储介质流程销毁。

## 升级

```bash
cd /opt/yyslide
sudo systemctl stop yyslide-cloud
sudo -u yyslide sqlite3 /var/lib/yyslide-cloud/yycloud.sqlite ".backup '/var/lib/yyslide-cloud/pre-upgrade-$(date +%F-%H%M%S).sqlite'"
# 更新项目文件后
sudo systemctl start yyslide-cloud
curl -fsS https://yyslide.ccwu.cc/health
```

## 安全检查清单

- `YYSLIDE_SERVER_SECRET_FILE` 指向仅 `yyslide` 可读的 0600 文件，并且没有提交到仓库。
- `YYSLIDE_CLOUD_HOST=127.0.0.1`，8787 没有公网暴露。
- 通过 HTTPS 访问 `https://yyslide.ccwu.cc`。
- Caddy/nginx 正常自动续签证书。
- `yycloud.sqlite`、服务端密钥和旧 JSON 加密归档都有独立、静态加密的备份。
- 数据目录中没有遗留 `db.json`、迁移暂存文件或 plaintext SQLite 备份。
- systemd 服务使用独立 `yyslide` 用户运行。
- 日志中没有 `YYSLIDE_INSECURE_DEV=1` 警告。

## 本地临时测试

只在本机临时测试时可以这样跑：

```bash
test_data_dir="$(mktemp -d)"
YYSLIDE_INSECURE_DEV=1 \
YYSLIDE_CLOUD_DATA="$test_data_dir" \
node server/yycloud.cjs
```

不要在服务器部署时使用 `YYSLIDE_INSECURE_DEV=1`，也不要把临时数据目录
当作正式数据或备份。
