# posts 模块

用户原创文章功能，对应数据库表 `posts`。

## 职责

提供文章的创建、更新、删除、列表、详情查询，以及"一键转正"记录功能。
与 Fumadocs (`/docs`) 体系完全隔离，不走 Git PR 流程。

## 子包说明

| 包 | 职责 |
|---|---|
| `model/` | `Post` record + `PostStatus` / `PostVisibility` 常量类 |
| `dto/` | `PostRequest`（写请求）/ `PostView`（详情）/ `PostSummaryView`（列表摘要）|
| `repository/` | `PostRepository` 接口 + `JdbcPostRepository` Spring JDBC 实现 |
| `service/` | `PostService`：核心业务逻辑（slug 生成/去重、owner 校验）|
| `controller/` | `PostController`：7 个 REST 端点，路径前缀 `/api/posts` |

## API 端点总览

```
POST   /api/posts                   创建文章（需登录）
PUT    /api/posts/{id}              更新文章（需登录 + owner）
DELETE /api/posts/{id}              删除文章（需登录 + owner）
GET    /api/posts/mine              我的文章（需登录）
GET    /api/posts/feed              公开 feed 列表（匿名可访问）
GET    /api/posts/{username}/{slug} 详情/分享页（匿名可访问）
POST   /api/posts/{id}/promote      记录转正 PR（需登录 + owner）
```
