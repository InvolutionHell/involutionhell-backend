# 后端安全不变量（Security Invariants）

本文档登记后端代码中**不可变更的安全保护点**。
每一条不变量都对应 `SecurityInvariantsTests` 中的回归测试，CI 自动跑。

## 维护规则

修改本文件涉及的代码时，**必须同时更新对应测试**。
删除任何一条不变量需在 PR 描述写明理由并 CC superadmin review。

每条不变量包含四个字段：
- **保护点**：被这条规则保护的代码位置（file path + 类/方法名）
- **测试**：验证这条规则的测试方法名（直接 `./mvnw test -Dtest=SecurityInvariantsTests` 跑得到）
- **为什么**：规则存在的理由——攻击场景 / 历史 / 业务约束
- **历史**：规则诞生时间与背景（commit、CR 报告、incident）

## 编号约定

`INV-XXX` 三位流水号，永不复用。删除条目时保留编号 + 标 `(retired)`，
新条目用下一个未占用号。这样 PR diff / 历史回溯永远稳定。

---

## INV-001 · superadmin 角色不能通过 API 授予

- **保护点**：
  - `usercenter/service/UserCenterService#updateAuthorization`
  - `events/controller/AdminUserController#setAdminRole`
- **测试**：
  - `SecurityInvariantsTests#admin不能通过PUT_users_authorization给自己加superadmin角色`
  - `SecurityInvariantsTests#admin不能通过PUT_users_authorization给他人加superadmin角色`
  - `SecurityInvariantsTests#admin可以通过PUT_users_authorization设置非superadmin的合法角色`（反向 happy path）
- **为什么**：superadmin 是 admin 的上位角色（能管理其他 admin 的角色绑定）。
  如果 admin 能通过任何 API 拿到 superadmin，整套 RBAC 边界失效。
  唯一合法的 superadmin 授予路径：DB 直接 `UPDATE user_accounts SET roles = ...`。
- **历史**：2026-05-07 三方 CR 的 attack chain A 起点（详见内部报告）。

---

## INV-002 · 不允许往他人的 chat 历史写消息

- **保护点**：`chat/controller/ChatHistoryController#save`
- **测试**：
  - `SecurityInvariantsTests#匿名调用不能往他人chatId写消息`
  - `SecurityInvariantsTests#登录用户不能往他人chatId写消息`
  - `SecurityInvariantsTests#chat的owner可以继续写自己的chat历史`（反向）
  - `SecurityInvariantsTests#匿名chat允许匿名继续写`（反向，保留匿名 → 登录迁移语义）
- **为什么**：chat 历史既是用户隐私也是 AI 训练 / 行为审计的输入。
  如果攻击者拿到他人 chatId（前端 log / share URL leak），匿名 POST 即可往
  victim 历史里塞污染消息。COALESCE 语义不会改 ownerId，但 INSERT INTO
  "Message" 已经发生——这是数据完整性 + 内容污染。
  匿名 chat 允许继续匿名写，保留"匿名 → 登录迁移"业务语义；
  一旦 chat 已绑定 ownerId，则只允许 owner 本人写。
- **历史**：2026-05-07 三方 CR 的 attack chain B 起点。

---

## INV-003 · 密码哈希必须用 bcrypt（legacy SHA-256 仅兼容路径）

- **保护点**：
  - `usercenter/service/PasswordService#hash`（始终输出 bcrypt）
  - `usercenter/service/PasswordService#matches`（dual-mode：bcrypt + legacy SHA-256）
  - `usercenter/service/AuthService#login`（lazy upgrade：登录成功后把 legacy hash 就地升级）
  - `src/main/resources/schema.sql`（seed bcrypt 哈希）
  - `docker/init-db/init.sql`（seed bcrypt 哈希）
  - `src/test/resources/test-schema.sql`（seed bcrypt 哈希）
- **测试**：
  - `PasswordServiceTests#hashGeneratesBcryptOutput`
  - `PasswordServiceTests#hashSamePasswordTwiceProducesDifferentOutput`（salt 随机性证据）
  - `PasswordServiceTests#matchesAcceptsLegacySha256Hash`（向后兼容）
  - `PasswordServiceTests#isLegacyHashIdentifiesPlainSha256`
  - `SecurityInvariantsTests#seed账号能用明文密码登录验证bcrypt端到端`
- **为什么**：原实现用单轮 SHA-256，无 salt 无 cost factor。
  DB 一旦泄露（备份、容器逃逸、pgAdmin 误导出），所有口令账号瞬间失守——
  GPU 一秒可跑数十亿哈希，rainbow table 已收录绝大部分常见明文。
  迁移策略：新写入一律 bcrypt；老 SHA-256 hash 通过 dual-mode matches 让用户能登录，
  登录成功时由 AuthService 就地升级。所有 seed 同步重写为 bcrypt 形式。
- **历史**：2026-05-07 三方 CR 的 attack chain B 加固项。

---

## INV-004 · user_follows 表必须随 schema 一并建立

- **保护点**：
  - `src/main/resources/schema.sql`（user_follows DDL + idx_user_follows_followee 索引）
  - `docker/init-db/init.sql`（同步 DDL）
  - `src/test/resources/test-schema.sql`（测试同步）
  - `usercenter/follows/FollowService`（SQL 调用方）
- **测试**：`SecurityInvariantsTests#user_follows表存在且字段可读写`
  （不直接调 FollowService.follow() 端到端，因 FollowService 用 PG 专属
  `ON CONFLICT (cols) DO NOTHING`，H2 PostgreSQL MODE 当前版本不识别——
  改打 JdbcTemplate 直读直写表，验证 schema drift 这一根因）
- **为什么**：FollowService 引用的 `user_follows(follower_id, followee_id, created_at)`
  之前在 schema.sql 与 init.sql 中都未定义，全新部署或新 Neon 数据库一访问
  `/api/user-center/follows/...` 就 `relation does not exist` 报 500。
  这是 issue #25 之外的额外 schema drift。CI 测试存在表 + 字段可读写作为
  长期回归探测器。
- **历史**：2026-05-07 三方 CR 发现的 schema drift（与 issue #25 类同）。

---

## INV-005 · docker-compose 不允许暴露公网 PG 端口或保留弱密码默认值

- **保护点**：`docker-compose.yml`（postgres 服务 ports 段、所有密码字段）
- **测试**：
  - `SecurityInvariantsTests#docker_compose里postgres端口必须绑127_0_0_1`
  - `SecurityInvariantsTests#docker_compose里不允许出现change_me弱密码默认值`
- **为什么**：PostgreSQL 主端口暴露公网 = 攻击面 +1（弱密码扫库 / 0day / TLS 退化）。
  pgAdmin 走 docker internal network 即可访问，不需要 host port mapping。
  `change_me` fallback 让部署者忘配 env 时仍能起服务，但起来的就是弱密码后台
  ——必须用 `${VAR:?...}` 形式强制部署期校验。
- **历史**：2026-05-07 三方 CR 加固项（端口部分历史已修，本次加测试 + 收紧密码默认值）。
