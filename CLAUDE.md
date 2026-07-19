# CLAUDE.md — backend

Claude Code 加载本文件；其它 agent 加载 `AGENTS.md`。两者的硬约束以
**`AGENTS.md` 为准**，本文件只做转发，避免双份维护漂移。

**动手前必读：**

- `AGENTS.md` — 改 DB schema 前的三处同步 + 全新贡献者启动检查等硬约束
- `SECURITY.md` — INV-001.. 安全不变量（改 auth/角色/密码/关注/端口前必读）
- 根 `../CLAUDE.md`（本机存在时）— 三仓库架构总览与跨服务边界

backend 架构目前在根 `CLAUDE.md` 里成篇覆盖。
