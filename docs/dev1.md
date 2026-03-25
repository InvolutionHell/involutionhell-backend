# 开发参考手册

## API 端点速查

| 说明 | 路径 |
|------|------|
| 后端监听 | `http://localhost:8080` |
| 发起 GitHub 登录（浏览器直跳） | `http://localhost:8080/oauth/render/github` |
| GitHub 回调（经 Next.js rewrite 代理） | `localhost:3000/api/auth/callback/github` → `localhost:8080/api/auth/callback/github` |
| 获取当前用户 | `GET /auth/me`（需 `satoken` header） |
| 退出登录 | `POST /auth/logout`（需 `satoken` header） |
| 健康检查 | `GET /actuator/health` |

---

## 用户 ID 体系说明

项目中存在**两套独立的用户 ID**，混淆会导致数据关联错误：

| 表 | 主键类型 | 管理方 | 说明 |
|----|---------|--------|------|
| `users` | `Int`（自增） | Prisma / NextAuth（已迁移，不再写入） | 遗留表，历史数据保留 |
| `user_accounts` | `BigInt`（自增） | Spring Boot / Sa-Token | **当前有效用户体系** |

- `Chat.userId` 和 `AnalyticsEvent.userId` 均为 `BigInt`，对应 `user_accounts.id`
- `doc_contributors.github_id` 为 `BigInt`，对应 `user_accounts.github_id`（GitHub 数字用户 ID）
- 前端 `UserView.id` 使用 TypeScript `number`，安全范围内（≤ 2^53）

---

## 前端服务端身份验证

前端 Next.js API Route 通过 `lib/server-auth.ts` 中的 `resolveUserId()` 验证用户：

```
请求携带 x-satoken header
    → Next.js API Route 调用 resolveUserId(req)
    → 服务端向后端 GET /auth/me 发起请求（BACKEND_URL 环境变量）
    → 返回 user_accounts.id（BigInt）或 null（匿名）
```

**使用方：**
- `frontend/app/api/chat/route.ts` — 保存 Chat 记录时关联用户
- `frontend/app/api/analytics/route.ts` — 保存 AnalyticsEvent 时关联用户

不要在 `resolveUserId` 以外的地方重新实现这段逻辑。

---

## 前后端职责分工现状（2026-03-26）

### 已迁移到后端

| 功能 | 迁移前 | 迁移后 |
|------|--------|--------|
| GitHub OAuth 登录 | NextAuth（前端） | JustAuth + Sa-Token（后端） |
| 会话管理 | NextAuth Session / Prisma `sessions` | Sa-Token `user_accounts` |
| 用户数据 | Prisma `users` 表 | `user_accounts` 表 |

### 前端 API Route 现状

| 路由 | 说明 | 状态 |
|------|------|------|
| `api/chat` | AI 对话，直接调外部 AI API | ⚠️ 与后端 `/openai` 重复，待统一 |
| `api/analytics` | 埋点写 Neon | 暂留前端，功能自洽 |
| `api/upload` | 上传到 Cloudflare R2 | 暂留前端，功能自洽 |
| `api/suggestions` | AI 生成建议问题 | 暂留前端 |
| `api/docs-tree` | Fumadocs 文档导航树 | 不迁移，Fumadocs 专属 |
| `api/indexnow` | SEO ping | 不迁移，构建侧逻辑 |

### TODO：Chat 双实现问题

**现象：** 后端已有 `OpenAiStreamController`（`/openai`），前端 `app/api/chat/route.ts` 是完全平行的另一套实现，两者均直接调 AI 接口，AI API Key 和模型配置分散在前后端各一份。

**方案：** 让前端 `/api/chat` 通过 Next.js rewrite proxy 到后端 `/openai`，AI Key 统一在后端配置，前端不再接触。实现方式与认证迁移一致。

**优先级：** 低，暂不处理。

---

## Sa-Token 会话流程

```
用户点击登录
  → 前端跳转 /oauth/render/github（后端直接重定向 GitHub）
  → GitHub 回调 /api/auth/callback/github（经 Next.js rewrite 转发给后端）
  → 后端 JustAuth 解析 AuthUser，查找或创建 user_accounts 记录
  → StpUtil.login(userId) 建立 Sa-Token 会话
  → 后端重定向到前端首页，URL 携带 ?token=xxx
  → 前端 AuthProvider 读取 token 存入 localStorage，清除 URL 参数
  → 后续请求通过 x-satoken header 或 satoken header 传递 token
```
