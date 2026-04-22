# Analytics · 历史路径匹配（doc_paths）

> 2026-04-22 起，`AnalyticsService.getTopDocs` 用 `doc_paths` 表承载 IA 重组前的历史路径，
> 不再在 Java 代码里维护硬编码的 `PATH_REWRITES` 前缀表。

## 问题来源

`/rank?tab=hot&window=all` 和 30D 榜单在 2026-04-19 那次 IA 重组之后几乎为空：

```
commit 6684884  feat(ia): reorganize docs → learn / community / career / projects
  app/docs/ai/*                      → app/docs/learn/ai/*
  app/docs/CommunityShare/*          → app/docs/community/*
  app/docs/jobs/interview-prep/*     → app/docs/career/interview-prep/*
  app/docs/computer-science/*        → app/docs/learn/cs/*
  app/docs/all-projects/*            → app/docs/projects/*
  ...
```

- `docs.path_current` 由 `frontend/scripts/backfill-contributors.mjs` 刷新，
  重组后写的是新路径（比如 `app/docs/learn/ai/multimodal/qwenvl/index.mdx`）。
- GA4 存的是**真实访问发生时**的 `pagePath`。30D / ALL 窗口里绝大多数历史流量
  用的还是老 URL（`/docs/ai/multimodal/qwenvl`）。
- `AnalyticsService.queryDocTitles` 本来只从 `docs.path_current` 做 JOIN，
  所以老 URL 一条都对不上 → 过滤完榜单几乎空。

## 解法

**一句话：让 `doc_paths` 做 URL 别名表，SQL 里 UNION 进来。**

```sql
SELECT d.title, regexp_replace(regexp_replace(d.path_current, '^app', ''),
                               '(/index)?\.(mdx|md)$', '') AS normalized
FROM docs d
WHERE d.path_current IS NOT NULL
UNION ALL
SELECT d.title, regexp_replace(regexp_replace(dp.path, '^app', ''),
                               '(/index)?\.(mdx|md)$', '') AS normalized
FROM doc_paths dp JOIN docs d ON d.id = dp.doc_id
```

`doc_paths` 在重组前后都会被 `backfill-contributors.mjs` 的 `upsertDocPath` 追加
（只增不删），理论上已经记下了每次的当前路径；但如果 DB 是重组之后才从备份恢复
/ 迁移过来的（比如 Neon → 自建 PG），老路径就漏了，需要一次性回填。

## 一次性回填脚本

[`backend/docs/migrations/2026-04-22-seed-ia-reorg-doc-paths.sql`](./migrations/2026-04-22-seed-ia-reorg-doc-paths.sql)
用 CTE + `ROW_NUMBER` 按最长前缀匹配，给每个移动过的 doc 写一条老路径。

执行方式：

```bash
docker exec -i involution-postgres psql -U neondb_owner -d involution_hell \
  < backend/docs/migrations/2026-04-22-seed-ia-reorg-doc-paths.sql
```

幂等的，反复跑安全。

## 下次 IA 重组要做什么

1. 前端像往常一样改 `next.config.mjs` 加前缀 redirect、移动 `app/docs/**` 文件。
2. 跑一次 `backfill-contributors.mjs`——新路径自动进 `doc_paths`。
3. **把新一条 `('app/docs/<新前缀>', 'app/docs/<旧前缀>')` 加到新的迁移 SQL
   并在生产执行一次**，把旧路径补进 `doc_paths`，覆盖重组前的存量流量。

这比之前往 `AnalyticsService.PATH_REWRITES` 硬编码一行然后重新构建部署后端要轻，
也不必两端同步：前端 redirect + SQL 一次性灌 doc_paths 就够了。

## 已知局限

- **Leetcode 拼音 slug**：`app/docs/career/interview-prep/leetcode/*.md` 的文件名
  仍是中文（如"平衡二叉树.md"），URL 会被 `lib/source.ts` 转成拼音
  （`ping-heng-er-cha-shu`）。这种情况下 GA4 拿到的 pagePath（拼音）和 docs
  表里的 path（中文）本来就对不上，UNION 了 doc_paths 也救不了。
  修需要单独开 issue：让 `docs` 里多存一个 `public_url` 列，由前端 sync 时把
  Fumadocs 渲染后的最终 slug 写进去。
- **点状 redirect**：`next.config.mjs` 里约 34 条单文件 301（swanlab / 若干
  cpp_backend 重命名）流量都很小，没有对应的 `doc_paths` 回填。需要时手动
  `INSERT INTO doc_paths (doc_id, path) VALUES (...);`。
