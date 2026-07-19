# AGENTS.md — backend 给 AI / contributor 的硬约束

配合根 `CLAUDE.md`（三仓库架构总览）与 `SECURITY.md`（INV-001.. 安全不变量）阅读。
这里只写**已经踩过、且对新人是毁灭性打击**的反模式。

## 改数据库前，先问"全新的人还能启动吗"

改 DB schema（新增表/列、写回填 DML、动 `schema.sql`）时，**默认受害者不是你**——
你的服务器/本地库早就有那些表和列，任何缺列/缺表的问题在你这儿都看不见。真正
会被打穿的是**第一次 clone 下来、`docker compose up` 的新贡献者**。改动前必须逐条过：

1. **三处 schema 必须同步改**，缺一即分裂：
   - `src/main/resources/schema.sql` — 唯一真相源，后端启动时执行（`SPRING_SQL_INIT_MODE=always`）
   - `docker/init-db/init.sql` — **只在数据卷首次创建时跑一次**，给全新 docker 贡献者建库
   - `src/test/resources/test-schema.sql` — H2（PostgreSQL MODE）测试镜像，`TIMESTAMPTZ→TIMESTAMP`、`JSONB→VARCHAR`

   致命点：`CREATE TABLE IF NOT EXISTS` 对**已存在的表是 no-op，补不上缺的列**。
   所以 `init.sql` 建的表若比 `schema.sql` 少列，schema.sql 永远修不好它——
   全新 docker 库会带着残缺表跑，登录 INSERT / 回填 SELECT 直接报
   `column "x" does not exist`。init.sql 的每张表列集必须与 schema.sql 逐列一致
   （这条正是 INV-004 `user_follows` 事故的教训扩展）。

2. **schema.sql 里的任何 DML 必须幂等**：它每次启动都重跑。回填用
   `INSERT ... ON CONFLICT DO NOTHING`，seed 用 `ON CONFLICT DO NOTHING/UPDATE`。
   非幂等语句会在第二次启动 `spring.sql.init` 报错、后端拒绝启动。

3. **绝不建议把 `SPRING_SQL_INIT_MODE` 改成 `never`**。schema.sql 幂等，`always`
   是安全默认（`.env.example` 默认即是）。改 never 后：docker 卷已存在 → init.sql
   不再跑，mode=never → schema.sql 不跑，别人**新加的表在你本地既不建也不报**，
   直到某个登录路径 500。曾经踩过，别把这条建议写回文档。

4. **不要把 DDL 直接敲进正在跑的（尤其生产）数据库**。把改动提交进上面三个 schema
   文件，让启动时 reconcile。核查生产只能只读（`to_regclass`、`SELECT`），
   写操作走代码 + 重启。

## DB 改动的验证配方（必须做，不能只信"我这能跑"）

用**一次性 throwaway 容器**模拟全新贡献者，跑完即弃，绝不碰真实库：

```bash
docker rm -f ih-fresh-test 2>/dev/null
docker run -d --name ih-fresh-test -e POSTGRES_USER=t -e POSTGRES_PASSWORD=t -e POSTGRES_DB=t \
  -v "$PWD/docker/init-db:/docker-entrypoint-initdb.d:ro" postgres:18-alpine
# 等 init.sql 跑完（pg_isready 轮询），再把 schema.sql 拷进去执行两遍：
docker cp src/main/resources/schema.sql ih-fresh-test:/tmp/schema.sql
docker exec ih-fresh-test psql -U t -d t -v ON_ERROR_STOP=1 -q -f /tmp/schema.sql   # 应 exit 0，无缺列崩溃
docker exec ih-fresh-test psql -U t -d t -v ON_ERROR_STOP=1 -q -f /tmp/schema.sql   # 再跑一遍验证幂等
docker rm -f ih-fresh-test
```

通过标准：两遍都 `exit 0`、无 `does not exist`、目标表/列存在。**只在既有服务器库上
测不算数**——它已有那些列，恰好把 init.sql 的分裂藏住。

## 备份

改数据前确认 `involution-pg-backup` 日备在跑（`@daily`，保留 30 天 + 8 周 + 12 月）。
破坏性迁移留回滚兜底；`docker compose down -v` 只在**本地**开发库用（会清空数据）。
