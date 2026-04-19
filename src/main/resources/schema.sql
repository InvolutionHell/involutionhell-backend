-- Java 侧自管理的用户账号表（Sa-Token 认证，非 Auth.js OAuth 用户）
-- 与 Prisma 管理的 users 表相互独立
CREATE TABLE IF NOT EXISTS user_accounts (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    roles         TEXT         NOT NULL DEFAULT '',
    permissions   TEXT         NOT NULL DEFAULT '',
    avatar_url    VARCHAR(500),
    email         VARCHAR(255),
    -- github_id 存储 GitHub 数字用户 ID，与 doc_contributors.github_id 对应
    github_id     BIGINT       UNIQUE
);

-- 偏好设置列（JSONB 顶层合并，前端可自由扩展 key）
ALTER TABLE user_accounts ADD COLUMN IF NOT EXISTS preferences JSONB NOT NULL DEFAULT '{}'::jsonb;

-- 默认种子账号（已存在则跳过）
-- admin / Admin@123456
-- alice / Alice@123456
-- auditor / Audit@123456
INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
VALUES ('admin',   'ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d', 'Admin',   TRUE, 'admin',   'user:profile:read,user:center:read,user:center:manage'),
       ('alice',   'b02bb998ecc1616148b9b4ba0405dbd4c224acd1bac059d59f0a07b3b1a68400', 'Alice',   TRUE, 'user',    'user:profile:read'),
       ('auditor', 'ccabaaba054fb98905b5b9ee47174f57cb6088e04b1526f08b872dc06eaa6bb9', 'Auditor', TRUE, 'auditor', 'user:profile:read,user:center:read')
ON CONFLICT (username) DO NOTHING;

-- 站点超管（superadmin）升级：
-- 原先尝试按 GitHub handle 做 seed，但 AuthService.loginByGithub 创建的 username 是
-- "github_{githubId}" 格式，seed 成其他 username 会插一批永远没人用的死账号。
-- 正确做法：
--   1. superadmin 首次 GitHub OAuth 登录过站点（AuthService 会建 github_{id} 账号）
--   2. 在 DB 跑一次下面这条 SQL 升级 roles；之后 superadmin 在 /admin/users 页面
--      给其他维护者打 admin 角色，不再碰 DB
--
--   UPDATE user_accounts
--   SET roles = 'superadmin,admin,user',
--       permissions = 'user:profile:read,user:center:read,user:center:manage'
--   WHERE github_id = 114939201;   -- longsizhuo，按需加其他 superadmin
--
-- superadmin 语义：拥有全部 admin 权限 + 能管理其他人的 admin 角色。
-- API 层禁止通过 /api/admin/users 接口授予或撤销 superadmin（防误操作锁死后台）。

-- =============================================================================
-- Events（活动）相关表
-- =============================================================================
-- 背景：站点有 Coffee Chat / Mock Interview / Career Journey / Open.Onion 等社群活动。
-- 原先维护在前端 data/event.json，新增/改时间都要改代码发版，迁到后端让管理员自助编辑。

-- 活动主表
-- title UNIQUE 给 seed 做真正的幂等（ON CONFLICT(title) DO NOTHING）兜底，顺便防
-- 管理员误建同名活动。实际产品上两场同名活动毫无意义，这个约束的代价是 0。
CREATE TABLE IF NOT EXISTS events (
    id             BIGSERIAL    PRIMARY KEY,
    title          VARCHAR(255) NOT NULL UNIQUE,
    description    TEXT         NOT NULL DEFAULT '',
    cover_url      VARCHAR(500),
    start_time     TIMESTAMPTZ,                        -- 活动开始时间，null 表示未排期
    end_time       TIMESTAMPTZ,                        -- 活动结束时间
    discord_link   VARCHAR(500),                       -- Discord 活动链接 / 频道邀请
    playback_url   VARCHAR(500),                       -- 回放链接（YouTube / Drive / 内部 doc）
    speakers       JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- [{"name","avatarUrl","profileUrl"}]
    tags           TEXT         NOT NULL DEFAULT '',   -- 逗号分隔，跟 user_accounts.roles 同风格
    status         VARCHAR(20)  NOT NULL DEFAULT 'published',  -- draft / published / archived / cancelled
    organizer_id   BIGINT       REFERENCES user_accounts(id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_events_start_time ON events(start_time DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS idx_events_status     ON events(status);

-- 活动"感兴趣"表（比 RSVP 轻量：不承诺出席，只表态关注）
-- 用户可多次点按切换；同一个 (event,user) 只保留一条记录，所以组合主键
CREATE TABLE IF NOT EXISTS event_interests (
    event_id   BIGINT      NOT NULL REFERENCES events(id)        ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_event_interests_user_id ON event_interests(user_id);

-- 种子：原来 data/event.json 里的 4 条活动。
-- 幂等策略：title 是 UNIQUE 约束列，重跑 schema.sql 或并发初始化时由 Postgres
-- 原生 ON CONFLICT(title) DO NOTHING 保证原子幂等，不会插重复。
-- startTime / endTime 先不填，管理员登录后在 /admin/events 里补时间再 publish。
INSERT INTO events (title, description, cover_url, discord_link, playback_url, tags, status)
VALUES
    ('Mock Interview',   '模拟面试专场：匹配面试官 1v1，结束即反馈，积累真实面试体感。', '/event/mockInterview.webp',
     'https://discord.gg/QHsjqezfC?event=1430500169299922965',
     'https://involutionhell.com/docs/jobs/event-keynote/event-takeway',
     'interview,mock', 'archived'),
    ('Coffee Chat',      '邀请业界嘉宾小范围交流，聊 career path、求职反思、日常 dev 体感。', '/event/coffeeChat.webp',
     'https://discord.com/invite/8AQZj7sa?event=1432010537402761348',
     'https://involutionhell.com/docs/jobs/event-keynote/coffee-chat',
     'career,chat', 'archived'),
    ('Career Journey',   '资深从业者分享完整职业路径 + 关键决策点。', '/event/careerJourney.webp',
     'https://discord.com/invite/8AQZj7sa?event=1432010537402761348',
     'https://involutionhell.com/docs/jobs/event-keynote/event-takeway',
     'career,sharing', 'archived'),
    ('Open.Onion',       '持续进行中的开源 / 内部项目协作节奏，参与即获得 contributor 标签。', '/event/openOnion.webp',
     'https://discord.gg/kJZFMr5chU?event=1477581193582088304',
     NULL,
     'project,open-source', 'published')
ON CONFLICT (title) DO NOTHING;

-- Chat / Message：前端 AI 对话历史持久化。
-- 历史：原 Next.js API route 用 Prisma 直连 Neon 写入；2026-04-17 把 Neon
-- 换成自建 Docker PG，Prisma 留在前端会导致前端写到 Neon 旧库、后端读自建
-- PG 的脏数据分叉。迁移方案 A：前端 onFinish 改 fetch backend /api/chat/sessions/save，
-- 持久化逻辑统一走后端。表名用 Prisma 风格的 PascalCase + 带引号列名，保留与
-- 原 Prisma schema 兼容，避免前端在切流量期间拿旧 client 读取时失败。
CREATE TABLE IF NOT EXISTS "Chat" (
    id          TEXT         PRIMARY KEY,
    "userId"    INTEGER,
    "createdAt" TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    "updatedAt" TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS "Chat_userId_idx" ON "Chat"("userId");

CREATE TABLE IF NOT EXISTS "Message" (
    id          TEXT         PRIMARY KEY,
    "chatId"    TEXT         NOT NULL REFERENCES "Chat"(id) ON DELETE CASCADE,
    role        TEXT         NOT NULL,
    content     TEXT         NOT NULL,
    "createdAt" TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS "Message_chatId_idx" ON "Message"("chatId");

-- =============================================================================
-- Community Shared Links（社区分享链接墙）相关表
-- =============================================================================
-- 背景：群友日常在微信群转发公众号/知乎等文章，走 Fumadocs 贡献门槛太高。
-- 这条链路与 Fumadocs 完全独立：用户粘 URL + 一句话推荐 → 后端抓 OG meta +
-- DeepSeek 异步分类审核 → 通过后进 /feed 瀑布流 → 卡片点击直接跳原文。
-- 设计见 ~/ih-wiki/Community-Shared-Links.md
--
-- status 枚举：
--   PENDING         - 刚提交，异步 worker 未处理
--   APPROVED        - 白名单 + AI 安全判定通过，公开展示
--   PENDING_MANUAL  - 非白名单，进人工待审
--   FLAGGED         - AI 判定 nsfw/ad/flame，进人工待审
--   REJECTED        - 人工拒绝
--   ARCHIVED        - 原文失效（HEAD 探活连续 2 次失败），主流不展示但作者可见
CREATE TABLE IF NOT EXISTS shared_links (
    id              BIGSERIAL    PRIMARY KEY,
    submitter_id    BIGINT       NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    url             TEXT         NOT NULL,
    url_hash        VARCHAR(64)  NOT NULL UNIQUE,      -- sha256(url) 做去重
    host            VARCHAR(255) NOT NULL,             -- 根域，严格匹配后的规范化 host
    recommendation  TEXT,                              -- 用户一句话推荐理由
    og_title        TEXT,
    og_description  TEXT,
    og_cover        TEXT,
    og_site_name    VARCHAR(255),
    og_fetch_error  TEXT,                              -- OG 抓取失败原因，降级展示时仍保留卡片
    category        VARCHAR(64),                       -- AI 分类枚举 slug
    flags           JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- {nsfw, ad, flame}
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    report_count    INT          NOT NULL DEFAULT 0,
    archived_at     TIMESTAMPTZ,                       -- 失效时间，ARCHIVED 时写入
    archived_reason VARCHAR(64),                       -- link_dead / manual / spam
    probe_fail_count INT         NOT NULL DEFAULT 0,   -- M9 失效探活连续失败计数，>=2 → ARCHIVED
    probe_last_at   TIMESTAMPTZ,                       -- 最近一次 HEAD 探活时间，用于跳过刚跑过的
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 已有库兼容（部署过 M1 版本的环境）：补上 probe 两列
ALTER TABLE shared_links ADD COLUMN IF NOT EXISTS probe_fail_count INT NOT NULL DEFAULT 0;
ALTER TABLE shared_links ADD COLUMN IF NOT EXISTS probe_last_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_shared_links_status_created ON shared_links(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_shared_links_category       ON shared_links(category) WHERE status = 'APPROVED';
CREATE INDEX IF NOT EXISTS idx_shared_links_submitter      ON shared_links(submitter_id, created_at DESC);

-- 举报表。同一人对同一条链接只能举报一次（组合唯一）。
-- 3 个独立举报自动下架逻辑由 Service 层维护 report_count。
CREATE TABLE IF NOT EXISTS link_reports (
    id          BIGSERIAL    PRIMARY KEY,
    link_id     BIGINT       NOT NULL REFERENCES shared_links(id)  ON DELETE CASCADE,
    reporter_id BIGINT       NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    reason      VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (link_id, reporter_id)
);

CREATE INDEX IF NOT EXISTS idx_link_reports_link ON link_reports(link_id);
