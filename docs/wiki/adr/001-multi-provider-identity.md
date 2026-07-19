---
type: adr
title: 多 Provider 身份体系与 OAuth state 防护
tags: [auth, oauth, identity, security]
intent: 身份体系设计决策
status: accepted
date: 2026-07-18
supersedes: []
superseded_by: []
# schema_source 留空：okf 的符号解析器是 Python-AST 专用，这个 Java/SQL repo
# 里它无法机器解析，权威代码指针改放 documents.symbols（纯标签，可 grep 可读）。
schema_source: []
documents:
  endpoints:
    - GET /oauth/render/{provider}
    - GET /api/auth/callback/{provider}
    - POST /api/auth/link/{provider}/start
    - POST /api/auth/link/{provider}/confirm
  symbols:
    - src/main/resources/schema.sql (user_identities 表 DDL + 回填)
    - src/main/java/com/involutionhell/backend/usercenter/model/UserIdentity.java
    - src/main/java/com/involutionhell/backend/usercenter/repository/UserIdentityRepository.java
    - src/main/java/com/involutionhell/backend/usercenter/service/AuthService.java (loginByGithub → M1 loginByProvider)
    - src/main/java/com/involutionhell/backend/usercenter/controller/OAuthController.java (render/callback)
---

# ADR-001：多 Provider 身份体系与 OAuth state 防护

完整讨论与对抗性 review 见 [RFC issue #42](https://github.com/InvolutionHell/involutionhell-backend/issues/42)。
本文只记结论和"为什么"；表结构、字段、约束以 `schema_source` 指向的代码为准，不在此复述。

## 背景

站点要接入多个第三方登录（Discord、Google……）。旧模型是单 provider 捷径：
`user_accounts.github_id` 列 + `username = "github_" + githubId`，每接一家都要改核心表，
且 provider 烧进了用户名。（历史巧合：库里废弃的 NextAuth `accounts` 表形状本来是对的，
Sa-Token 迁移时被塌缩掉了。）

## 决策

**账号与登录方式分表**：`user_accounts` = 人（无任何 provider 字段）；`user_identities` =
登录方式，每行一个 `(provider, provider_user_id)`。接新 provider = OAuth App 注册 +
两个 env key + AuthRequest 工厂一个 case，零 schema 变更。

关键约束的 why（DDL 见 schema.sql）：

- `UNIQUE (provider, provider_user_id)` — 一个第三方身份只能绑一个账号。
- `UNIQUE (user_id, provider)` — 同账号同 provider 至多一个身份：`/u/{githubId}`
  canonical URL 和贡献归属都假设 1:1，放开是一句 DROP，收紧要洗数据。
- FK `ON DELETE CASCADE` — 删号不留幽灵身份。注意"无 FK"惯例只适用于 Prisma
  跨系统引用（根 CLAUDE.md），后端表引后端表照常加 FK。
- 启动回填用无冲突目标的 `ON CONFLICT DO NOTHING` — schema.sql 在
  `SPRING_SQL_INIT_MODE=always` 的环境随启动执行（默认 never），必须幂等
  （回归测试从 classpath 提取真实语句执行两遍验证）。全新库走
  docker/init-db/init.sql（三处 schema 同步惯例，见 INV-004 的 user_follows 教训）。
  回填只治"行缺失"不治"值变化"；**M2 解绑 github 必须同时清空 github_id 列**，
  否则下次执行回填会静默复活已撤销的绑定。

**GitHub 的特殊性下沉到业务层**：认证层 provider 平权；贡献归属、排行榜、认领档案
查 `provider = 'github'`。文档是 git-based 是业务事实，不泄漏进认证设计。

## 统一登录 / 绑定流程与 state 协议

**原则：state 是不透明的一次性 nonce，不携带任何身份信息；callback 永远不信任
state 里的用户身份**（登记为安全不变量 INV-007，随 M1 落进 SecurityInvariantsTests；
INV-006 已被"付费 LLM 端点限流"占用，编号按 SECURITY.md 流水规则永不复用）。
真实信息挂在服务端 intent 记录上（nonce 为 key，Caffeine 存储，5 分钟 TTL）。

- **登录**（无会话）：`/oauth/render/{provider}` 生成 nonce → 存 intent{mode=login} →
  种 `oauth_flow=<nonce>` cookie（httpOnly + **SameSite=Lax**，Strict 会把跨站顶级
  导航的 cookie 剥掉）→ 跳 provider。callback 核对 URL state == cookie nonce，
  防登录 CSRF：攻击者无法向受害者浏览器种自己的 cookie。
- **绑定**（已登录，从设置页发起）：satoken 在 localStorage，callback（provider 发起的
  顶级 GET）拿不到会话，所以把"你是谁"提前到发起时捕获——
  1. `POST /api/auth/link/{provider}/start`（fetch 带 satoken，可认证）→ 服务端记
     intent{mode=bind, userId=当前会话} → 种 cookie → 返回授权链接；
  2. callback 核对 state==cookie，把 provider 身份暂存进 intent，跳回
     `settings?link_confirm=<nonce>`；
  3. 前端确认页 → `POST .../confirm`（再带 satoken）→ 服务端二次核对
     当前会话 == intent.userId → 插入 identity。
  绑定劫持（攻击者发起流程诱导受害者授权）被 cookie 那关挡住：受害者浏览器没有
  攻击者的 `oauth_flow` cookie。
- 绑定回跳契约：`settings?linked={provider}` / `?link_error=identity_taken`（撞
  UNIQUE 是必然出现的用户可见错误，不混进 oauth_failed）。

## 其他已定结论

- **留 JustAuth，不回 Auth.js/NextAuth**：Auth.js 是前端库，搬回等于推翻 Sa-Token
  迁移、把认证边界移回前端；JustAuth 已覆盖 OAuth 协议与 provider 目录，真正要手写
  的只有 provider→user 映射（本表的业务逻辑，换任何库都躲不掉）。Discord 若不在
  JustAuth 内置列表，写自定义 AuthSource（约 30 行）。
- **intent / state 存储**：单实例进程内（Caffeine）够用；触发迁移的条件是**多实例**
  （GraalVM native 部署常伴随），届时连同 Sa-Token session、JustAuth state cache
  一起迁 Redis——注意本栈目前没有自己的 Redis（机器上两个 Redis 容器分属
  infisical 和 umami，不共享），迁移意味着新容器 + 解开 pom 里注释掉的依赖。
- **密码语义**：第三方注册用户的 `password_hash` 用不可用 sentinel `'!'`
  （discord-bridge 先例），配 `hasUsablePassword()` 判定；"解绑不得移除最后一种
  登录方式"的判定中，不可用密码不算登录方式，否则 OAuth 用户解绑唯一身份后永久锁死。
- **新用户 username 生成**：provider login 转 slug + 冲突短随机后缀；**禁纯数字**
  （撞 `/u/` 路由"纯数字=github_id"的解析约定）、禁 provider 前缀。存量
  `github_<id>` 用户名永不强迁。
- **资料刷新只填空缺字段**：多 provider 下 last-login-wins 会互相覆盖头像、
  用未验证邮箱覆盖 email。
- **邮箱关联只信"provider 已验证"的邮箱**。第三方登录时 identity 查不到，若
  provider 的**已验证**邮箱唯一匹配到已有账号 → 自动挂靠（防分叉，主路径，见
  `AuthService.autoLinkByVerifiedEmailOrCreate`）。**禁止用未验证邮箱合并**——
  攻击者拿受害者邮箱注册的第三方号无法把邮箱标 verified（要点验证链接=控制邮箱），
  所以已验证邮箱自动关联不增加攻击面（Auth0/Keycloak 同款）。多个匹配时保守建新号。
- **规范邮箱（用户自有 OTP）** 是后续独立子系统：注册时用我们自己的验证码验一次邮箱，
  使 email 成为账号的规范已验证身份，覆盖"provider 没给邮箱/未验证"的情况。
  **前置**：需要事务邮件服务 + `noreply@involutionhell.com` 发信域名——不能用
  ChatBot 那个个人 Gmail（额度/投递率/隐私都不行）。待发信基础设施定了再做。
- **资料刷新只填空缺字段**：多 provider 下 last-login-wins 会互相覆盖头像、
  用未验证邮箱覆盖 email。自动关联挂靠时**不覆盖**已有账号资料。

## 迁移阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| M0 | 建表 + 幂等回填 + repository | ✅ PR #43 |
| M1 | `loginByProvider` 统一流程（username 主查 + identity 双写） + state/cookie 硬化 + INV-007；`github_id` 列双写沿用 | ✅ 本 PR |
| M2a | 解绑 + 列表后端（解绑锁死防护 + github 解绑清 github_id 列） | ✅ 本 PR |
| 防分叉 | 已验证邮箱自动关联（登录时挂靠而非分叉） | ✅ 本 PR |
| M3 后端 | Discord provider（自定义 AuthSource）+ OAuth 端点泛化为 {provider} | ✅ #48 |
| M3 前端 | 登录页 Discord 按钮 + 设置页身份 UI | ✅ #382/#383 |
| OTP 注册 | 自有验证码验邮箱→规范身份（**待事务邮件基础设施**） | 待做 |
| M2b 绑定 | 设置页"连接新 provider"（可选，次要路径） | 待做 |
| M4 | identities 稳定一个版本后删 `github_id` 列（单独拆期，保回滚路径） | 待做 |
