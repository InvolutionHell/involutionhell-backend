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

### 登录 pgAdmin

1. 浏览器打开 `http://<server>:8082`
2. 用户名密码见 `.env` 的 `PGADMIN_EMAIL` / `PGADMIN_PASSWORD`
3. 左侧树直接显示预注册的 `InvolutionHell (local)`，双击连上就能用

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
