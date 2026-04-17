# 数据库运维手册（自建 Docker PostgreSQL）

> 2026-04-17 起，生产/开发均从 Neon 迁到本机 compose 起的 `postgres:18-alpine`。
> 迁移动因：Neon 免费月度额度（100 CU-h）耗尽后计算节点被暂停，
> 所有业务请求报错。自建后无配额限制，数据和延迟都本地可控。

## 架构总览

```
docker-compose.yml 里四个相关服务：
  postgres          — PostgreSQL 18 主库，数据卷 involution-postgres-data（命名卷，持久化）
  backend           — Spring Boot，通过内网 jdbc:postgresql://postgres:5432/involution_hell 访问
  pg-backup         — prodrigestivill/postgres-backup-local，每天 03:00 跑 pg_dump，保留 30 天日备/8 周周备/12 月月备
  pgadmin           — Web GUI，http://<host>:8082，带完整 Backup/Restore 按钮
```

备份文件写入 `pg-backups` 命名卷，pgAdmin 也挂载同一个卷到
`/var/lib/pgadmin/storage/admin_involutionhell.com/backups/`，
在 pgAdmin 的 Restore 对话框里直接选得到。

## 常用操作

### 登录 pgAdmin（关键：走 sa-token cookie 校验，不是 pgAdmin 自己的账号密码）

**生产环境**

pgAdmin 容器跑在 `SERVER_MODE=False`（desktop 模式，**自身无登录页**，进去就能操作 DB）。
安全把门**不在 pgAdmin**，而是外层 Caddy 的 `forward_auth` 钩子：

```
浏览器 → api.involutionhell.com/admin/pgadmin/*
          │ 携带 cookie: satoken=xxx（Domain=.involutionhell.com）
          ▼
        Caddy handle /admin/pgadmin/* {
          forward_auth 127.0.0.1:8080 {
              uri /api/admin/pgadmin-check   ← 后端接口带 @SaCheckRole("admin")
              copy_headers Cookie            ← satoken cookie 透传
          }
          reverse_proxy 127.0.0.1:8082       ← 只有 forward_auth 200 才到这里
        }
```

**用户视角**：
1. 先在主站 `involutionhell.com` 用 GitHub OAuth 登录
2. 登录成功时前端会把 satoken **双写**：
   - `localStorage.satoken`：给同源 fetch 手动附 header 用
   - `cookie satoken=...; Domain=.involutionhell.com`：给 api 子域直连用
3. 随便哪条都能进 pgAdmin：
   - 点主站 `/admin/database` 页面的 "打开 pgAdmin" 按钮
   - 直接敲 `https://api.involutionhell.com/admin/pgadmin/`
4. 浏览器自动带 cookie → Caddy forward_auth → 后端看 cookie + 查角色 → admin 就放过
5. 非 admin 或未登录 → 401（浏览器看到 Cloudflare/Caddy 的 401 错误页）

**不需要也不应该**再输 pgAdmin 自己的 email/password——`.env` 里 `PGADMIN_EMAIL`/`PASSWORD`
只是 desktop 模式下 pgAdmin 容器初始化用的占位，用户侧感知不到。

**本机联调**：直接 `ssh -L 8082:127.0.0.1:8082 <server>` 然后浏览器开
`http://localhost:8082/admin/pgadmin/`。这条路径绕过 Caddy，也就没有 forward_auth
校验——管理员自己机器上专用，不对外。

左侧树都能看到预注册的 `InvolutionHell (local)`，双击即连。

### 反向代理 / forward_auth 架构

```
involutionhell.com（主站 / Vercel）
   │ 登录成功 → 前端 lib/use-auth.tsx 写 cookie: satoken=xxx; Domain=.involutionhell.com
   │
   ├─ /admin/database 页面  →  Link target=_blank  →  api.involutionhell.com/admin/pgadmin/
   │
   └─ 或者用户直接在地址栏敲 api.involutionhell.com/admin/pgadmin/
          │ 浏览器自动带 Domain=.involutionhell.com 的 satoken cookie
          ▼
      Caddy (global-caddy-gateway, host 网络)
          │ forward_auth 127.0.0.1:8080  uri=/api/admin/pgadmin-check
          │   ├─ 200  → 继续代理
          │   └─ 非 200 → 拒绝
          ▼
      127.0.0.1:8082 (pgAdmin 容器, SERVER_MODE=False)
```

**pgAdmin 容器环境变量**：
- `SCRIPT_NAME=/admin/pgadmin`：让 pgAdmin 自生成的 URL 自带前缀（含登录跳转 / CSS）
- `PGADMIN_CONFIG_SERVER_MODE=False`：desktop / single-user，**不渲染登录页**。
  安全由外层 forward_auth 把守
- `PGADMIN_CONFIG_MASTER_PASSWORD_REQUIRED=False`：不用 master password 二次加密
- `PGADMIN_CONFIG_X_FRAME_OPTIONS=''`：清空默认 DENY，Caddy 负责 CSP
- 容器端口只绑 `127.0.0.1:8082`，不对公网开，唯一公网入口是 Caddy

**Caddy 配置**（`/home/ubuntu/caddy-gateway/Caddyfile`，不在本仓库）：

```caddy
api.involutionhell.com {
    handle /admin/pgadmin/* {
        forward_auth 127.0.0.1:8080 {
            uri /api/admin/pgadmin-check
            copy_headers Cookie
        }
        header {
            -X-Frame-Options
            Content-Security-Policy "frame-ancestors 'self' https://involutionhell.com https://*.involutionhell.com https://*.vercel.app http://localhost:3000 http://localhost:3010"
        }
        reverse_proxy 127.0.0.1:8082 {
            header_up X-Script-Name /admin/pgadmin
            header_up X-Scheme https
            header_up X-Forwarded-Proto https
        }
    }
    handle { reverse_proxy 127.0.0.1:8080 }
}
```

**后端端点**（`AdminInfraController.java`）：

```java
@RestController
@RequestMapping("/api/admin")
public class AdminInfraController {
    @GetMapping("/pgadmin-check")
    @SaCheckRole("admin")
    public ApiResponse<Void> pgadminCheck() { return ApiResponse.okMessage("authorized"); }
}
```

sa-token 默认从 header **和** cookie 同时读（`is-read-cookie=true` 默认开），
所以无论是同源 fetch 带 satoken header、还是跨子域的新标签页靠 cookie 自动带，
都能命中同一套角色校验。

**前端 cookie 同步**（`lib/use-auth.tsx` 的 `syncTokenCookie`）：

登录成功 / 每次刷新有效 session 时把 `localStorage.satoken` 复制一份到 cookie，
`Domain=.involutionhell.com; Max-Age=2592000`。localhost 开发时不带 Domain。
登出 / token 失效时反向清 cookie。

### 手动备份（立刻打一个快照）

```bash
docker exec involution-pg-backup /backup.sh
# 产物：pg-backups 卷里 last/daily/weekly/monthly 各一份
# 文件名示例：involution_hell-20260417-201149.sql.gz（plain SQL + gzip）
```

### 查看历史备份

```bash
docker exec involution-pg-backup ls -lh /backups/daily/
docker exec involution-pg-backup ls -lh /backups/weekly/
docker exec involution-pg-backup ls -lh /backups/monthly/
```

或者在 pgAdmin 里：Tools → Storage Manager → `backups/`。

### 恢复到指定时间点（命令行）

```bash
# 1. 选一个备份文件
FILE=involution_hell-20260417.sql.gz

# 2. 停写入（避免恢复期间 backend 又写进去造成冲突）
docker stop involution-hell-backend

# 3. 清空当前库
docker exec involution-postgres psql -U neondb_owner -d postgres \
  -c "DROP DATABASE involution_hell WITH (FORCE);" \
  -c "CREATE DATABASE involution_hell OWNER neondb_owner;"

# 4. 恢复（plain SQL.gz 格式用 psql + gunzip；若是 -Fc 自定义格式则改用 pg_restore）
docker exec involution-pg-backup sh -c \
  "gunzip -c /backups/daily/$FILE | psql -h postgres -U neondb_owner -d involution_hell"

# 5. 启回 backend
docker start involution-hell-backend
```

### 恢复到指定时间点（pgAdmin GUI）

pgAdmin 的 Restore 对话框默认只支持 custom/tar/directory 格式，
`.sql.gz` 不直接支持。解决方式二选一：

- **Query Tool 导入**：右键数据库 → Query Tool → 把 `.sql.gz` 解压后粘贴 SQL 执行
- **改用自定义格式备份**：见下方「切换备份格式」章节

### 切换备份格式到 pg_restore 兼容的 `-Fc`（可选）

在 `docker-compose.yml` 的 `pg-backup` 服务改：

```yaml
environment:
  POSTGRES_EXTRA_OPTS: "-Fc"
  BACKUP_SUFFIX: ".dump"
```

然后重建：`docker compose up -d --force-recreate pg-backup`。
之后 pgAdmin 右键数据库 → Restore → 文件类型选 Custom，直接点按钮即可。
代价：备份文件比 gzip 压缩的略大一点。

## 连接凭证

所有 DB 连接信息在根目录 `.env`：

| 变量 | 用途 |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | postgres 容器初始化 + backup 脚本 |
| `SPRING_DATASOURCE_URL` / `*_USERNAME` / `*_PASSWORD` | backend 的 JDBC 连接 |
| `PGHOST` / `PGPORT` / `PGUSER` / `PGPASSWORD` / `PGDATABASE` | psql 兼容环境变量（部分脚本依赖） |
| `PGADMIN_EMAIL` / `PGADMIN_PASSWORD` | pgAdmin Web 登录 |

> 账号沿用 `neondb_owner` 是为了最小化改动，不是生产建议。
> 后续可以跑 `ALTER USER neondb_owner RENAME TO involution;` 改得更整洁。

## 初始化 pgpass 文件

`docker/pgadmin/pgpass` 被 .gitignore，首次部署要在服务器上生成：

```bash
cd ~/involution-hell
set -a && . ./.env && set +a
printf 'postgres:5432:*:%s:%s\n' "$POSTGRES_USER" "$POSTGRES_PASSWORD" \
  > docker/pgadmin/pgpass
# pgAdmin 容器内 UID=5050，必须把文件 owner 改成 5050，且权限 0600
sudo chown 5050:5050 docker/pgadmin/pgpass
sudo chmod 600 docker/pgadmin/pgpass
```

没这步 pgAdmin 会 restart loop，日志里是 `cp: can't open '/pgpass': Permission denied`。

## 数据迁移的历史记录

- 2026-04-17：Neon `involution-hell` 项目 → 本机 `involution-postgres` 容器
  - dump 方法：`pg_dump -Fc` 从 pooler endpoint 拉出 14 张表、5487 行
  - 验证：先导入 `involution_hell_test` 对过行数，再切流量
  - `.env` 里 `PGHOST` 由 Neon endpoint 改为 `postgres`（compose 服务名）
