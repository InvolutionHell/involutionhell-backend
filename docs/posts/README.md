# posts 模块文档

用户原创文章功能（直接落库，不走 Git PR）。

## 背景

站内编辑器写完后可以直接发布到 `posts` 表，在 `/feed` 原创 Tab 和个人主页展示，与 Fumadocs (`/docs`) 精选知识库完全隔离。文章带"转正"入口，作者可一键跳 GitHub 新建文件页把文章升级为 Fumadocs contributor 贡献。

## API 端点

所有端点前缀 `/api/posts`（后端 `http://localhost:8081`）。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `POST` | `/api/posts` | 需登录 | 创建文章，返回 201 + PostView |
| `PUT` | `/api/posts/{id}` | 需登录 + owner | 更新文章内容 |
| `DELETE` | `/api/posts/{id}` | 需登录 + owner | 物理删除 |
| `GET` | `/api/posts/mine` | 需登录 | 当前用户所有文章（全状态）|
| `GET` | `/api/posts/feed` | **公开** | 已发布公开文章列表，分页 |
| `GET` | `/api/posts/{username}/{slug}` | **公开** | 详情/分享页 |
| `POST` | `/api/posts/{id}/promote` | 需登录 + owner | 记录转正 PR 链接 |

### 鉴权 Header

```
satoken: <token值>
```

不是 `Authorization: Bearer`，与站内其他登录态接口一致。

### POST /api/posts 请求体

```json
{
  "title":       "string（必填）",
  "description": "string | null",
  "tags":        ["string"] | null,
  "contentMd":   "string（必填，原始 markdown）",
  "coverUrl":    "string | null",
  "slug":        "string | null  ← 不传则由 title 自动生成 kebab-case"
}
```

### PostView 响应结构（详情，含 contentMd）

```json
{
  "id":                 1,
  "slug":               "my-first-post",
  "title":              "...",
  "description":        "...",
  "tags":               ["tag1"],
  "contentMd":          "# markdown 正文",
  "coverUrl":           null,
  "visibility":         "PUBLIC",
  "status":             "PUBLISHED",
  "promotedPrUrl":      null,
  "promotedAt":         null,
  "viewCount":          0,
  "createdAt":          "2026-05-24T15:12:07.881679Z",
  "updatedAt":          "2026-05-24T15:12:07.881679Z",
  "authorUsername":     "alice",
  "authorDisplayName":  "Alice",
  "authorAvatar":       null
}
```

前端直发后跳转路径：`/u/${data.authorUsername}/posts/${data.slug}`

### PostSummaryView 响应结构（列表摘要，无 contentMd）

```json
{
  "id":                1,
  "slug":              "my-first-post",
  "title":             "...",
  "description":       "...",
  "tags":              ["tag1"],
  "coverUrl":          null,
  "visibility":        "PUBLIC",
  "status":            "PUBLISHED",
  "promoted":          false,
  "viewCount":         0,
  "createdAt":         "2026-05-24T15:12:07.881679Z",
  "authorUsername":    "alice",
  "authorDisplayName": "Alice",
  "authorAvatar":      null
}
```

## 数据库表

```sql
CREATE TABLE IF NOT EXISTS posts (
    id              BIGSERIAL    PRIMARY KEY,
    author_id       BIGINT       NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    slug            VARCHAR(128) NOT NULL,
    title           TEXT         NOT NULL,
    description     TEXT,
    tags            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    content_md      TEXT         NOT NULL,
    cover_url       TEXT,
    visibility      VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',
    promoted_pr_url TEXT,
    promoted_at     TIMESTAMPTZ,
    view_count      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (author_id, slug)
);
```

`SPRING_SQL_INIT_MODE=always` 启动时自动建表，无需手动迁移。

## SaTokenConfigure 白名单

`GET /api/posts/feed` 和 `GET /api/posts/*/*` 已加入公开白名单，匿名可访问。
写接口（POST/PUT/DELETE）和 `/mine` 由方法级 `@SaCheckLogin` 守卫。

## 部署说明

feat/posts-module 合并 main 后，重建后端镜像时 posts 模块随新镜像一次性上线：

```bash
cd /home/ubuntu/involution-hell
git pull origin main
docker compose build backend
docker compose up -d backend
```

新镜像启动时 `schema.sql` 自动追加 `posts` 表（`IF NOT EXISTS` 幂等），已有数据不受影响。
