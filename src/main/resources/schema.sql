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
--
-- 哈希格式：bcrypt(cost=10) —— INV-003 起点。老库里仍可能保留裸 SHA-256 哈希，
-- 由 PasswordService.matches 兼容识别 + AuthService 在登录成功后做 lazy upgrade
-- 就地迁移为 bcrypt。生产首次部署的新库直接使用 bcrypt seed，不再有 SHA-256 裸值。
INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
VALUES ('admin',   '$2b$10$Bfnw8v.BsXeZVPbre94sJeokgEfKuCLsdH7ckJxzxn5nxirHJHmP.', 'Admin',   TRUE, 'admin',   'user:profile:read,user:center:read,user:center:manage'),
       ('alice',   '$2b$10$BmtVOmPK8Os/xreOTImsdec1fAA8Y9iTm1D823swYucSI2NDFdk.q', 'Alice',   TRUE, 'user',    'user:profile:read'),
       ('auditor', '$2b$10$/1OfzhrA6CITrjJsDbzk.uMLq6cHa/iOP./wL2BAPo9t7QRq7Ca5W', 'Auditor', TRUE, 'auditor', 'user:profile:read,user:center:read')
ON CONFLICT (username) DO NOTHING;

-- Discord 桥接系统账号（不可登录）。
-- 双重禁用：
--   1. password_hash='!' —— 非 sha256 合法十六进制，口令校验永远失败
--   2. enabled=FALSE     —— 未来若有人重置密码 / 误改口令，enabled 仍挡住登录
-- 该账号仅被 SharedLinkService.submitInternal 取 submitter_id 用（FK 不 care enabled），
-- 真实提交人名放在 recommendation 里（"来自 Discord @xxx"）。
--
-- 注意：这里用 DO UPDATE 而非 DO NOTHING，是因为老版本 seed 落过 enabled=TRUE，
-- 每次启动 reconcile 回 FALSE，可阻断历史遗留记录再次被误启用。
-- roles/permissions/password_hash 也一起回写，保证行的约束不会漂移。
INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
VALUES ('discord-bridge', '!', 'Discord Bridge', FALSE, 'bridge', '')
ON CONFLICT (username) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    display_name  = EXCLUDED.display_name,
    enabled       = EXCLUDED.enabled,
    roles         = EXCLUDED.roles,
    permissions   = EXCLUDED.permissions;

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
-- 登录身份（user_identities）
-- =============================================================================
-- 一个账号可挂多个第三方登录方式（github / discord / google / ...），认证层
-- provider 平权；GitHub 的特殊性（贡献归属）只体现在业务层 provider='github'
-- 的查询里。设计决策与 OAuth state 防护协议见 docs/wiki/adr/001-multi-provider-identity.md。
--
-- 约束语义：
--   UNIQUE (provider, provider_user_id) —— 一个第三方身份只能绑一个账号
--   UNIQUE (user_id, provider)          —— 一个账号同一 provider 只能绑一个身份
--     （/u/{githubId} canonical URL 与贡献归属都假设 1:1，放开是 DROP CONSTRAINT
--       一句话，收紧要洗数据，故默认收紧）
--   CHECK (provider = lower(provider))  —— provider 规范化小写，防大小写分裂
--   FK ON DELETE CASCADE               —— 删号不留幽灵身份（否则登录路径会命中
--     不存在的账号）
CREATE TABLE IF NOT EXISTS user_identities (
    id                   BIGSERIAL    PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    provider             VARCHAR(32)  NOT NULL CHECK (provider = lower(provider)),
    provider_user_id     VARCHAR(255) NOT NULL,
    email_at_link        VARCHAR(255),
    display_name_at_link VARCHAR(255),
    linked_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login_at        TIMESTAMPTZ,
    UNIQUE (provider, provider_user_id),
    UNIQUE (user_id, provider)
);

-- 存量 GitHub 身份回填。本文件在 SPRING_SQL_INIT_MODE=always 时随启动执行
-- （默认 never，见 application.properties），ON CONFLICT 无冲突目标保证重跑幂等。
-- 边界（详见 ADR-001）：
--   1. 只治"行缺失"，不治"值变化"——github_id 改过值的账号，identity 行不会跟着
--      变（撞 UNIQUE(user_id,provider) 被跳过），值同步是 M1 双写逻辑的责任；
--   2. 只要 github_id 列仍有值，删除的 github identity 行会在下次执行时被重新
--      插入——因此 M2 解绑 github 时必须同时清空 user_accounts.github_id，
--      否则重启会静默复活用户已撤销的绑定。
-- github_id 列在 user_identities 稳定运行一个版本前保持双写（M1-M3，M4 删列）。
INSERT INTO user_identities (user_id, provider, provider_user_id)
SELECT id, 'github', CAST(github_id AS VARCHAR)
FROM user_accounts
WHERE github_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- =============================================================================
-- 关注关系（user_follows）
-- =============================================================================
-- FollowService 用 (follower_id, followee_id, created_at) 三列读写。
-- 复合主键即唯一约束，配合 follow() 的 ON CONFLICT DO NOTHING 实现幂等。
-- idx_user_follows_followee 给"查谁关注我（粉丝列表）"路径加倒序索引。
CREATE TABLE IF NOT EXISTS user_follows (
    follower_id BIGINT      NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    followee_id BIGINT      NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX IF NOT EXISTS idx_user_follows_followee
    ON user_follows(followee_id, created_at DESC);

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
    archived_reason VARCHAR(64),                       -- 系统归档原因：link_dead / manual / spam
    admin_note      TEXT,                              -- 管理员人工审核备注（reject 原因 / approve 评语）
    probe_fail_count INT         NOT NULL DEFAULT 0,   -- M9 失效探活连续失败计数，>=2 → ARCHIVED
    probe_last_at   TIMESTAMPTZ,                       -- 最近一次 HEAD 探活时间，用于跳过刚跑过的
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 已有库兼容（部署过 M1 版本的环境）：补上 probe 两列 + admin_note
ALTER TABLE shared_links ADD COLUMN IF NOT EXISTS probe_fail_count INT NOT NULL DEFAULT 0;
ALTER TABLE shared_links ADD COLUMN IF NOT EXISTS probe_last_at TIMESTAMPTZ;
-- admin_note：管理员 approve/reject 时的备注（reject 原因、approve 评语等）
-- 与 archived_reason 区分：后者是系统归档原因（link_dead 等），前者是人工审核原因
ALTER TABLE shared_links ADD COLUMN IF NOT EXISTS admin_note TEXT;

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

-- =============================================================================
-- Posts（用户原创文章）相关表
-- =============================================================================
-- 背景：站内编辑器写完后直接落库，不走 Git PR，与 Fumadocs(/docs) 体系完全隔离。
-- 展示位置：/feed 原创 Tab + /u/{username}/posts 个人文章列表 + 详情/分享页。
-- SEO 隔离：visibility=PUBLIC 但页面带 noindex，不进 sitemap，不被搜索引擎收录。
-- 转正：文章上有"一键转正"按钮，跳 GitHub 新建文件页半自动发 PR，promoted_pr_url 记录链接。
--
-- visibility 枚举：
--   PUBLIC   - 公开（任何人可访问，noindex 隔离 SEO）
--   UNLISTED - 仅凭链接访问（预留，MVP 暂不暴露给前端）
--
-- status 枚举：
--   DRAFT     - 草稿（预留，MVP 阶段前端发布直接走 PUBLISHED）
--   PUBLISHED - 已发布，对外可见
CREATE TABLE IF NOT EXISTS posts (
    id              BIGSERIAL    PRIMARY KEY,
    author_id       BIGINT       NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    slug            VARCHAR(128) NOT NULL,            -- 分享 URL 用，作者内唯一
    title           TEXT         NOT NULL,
    description     TEXT,
    tags            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    content_md      TEXT         NOT NULL,            -- 原始 markdown（图片已是 R2 公开 URL）
    cover_url       TEXT,
    visibility      VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC',   -- PUBLIC / UNLISTED
    status          VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',-- DRAFT / PUBLISHED
    promoted_pr_url TEXT,                             -- 转正后记录 GitHub PR 链接
    promoted_at     TIMESTAMPTZ,
    view_count      INT          NOT NULL DEFAULT 0,  -- 预留曝光计数
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (author_id, slug)
);

CREATE INDEX IF NOT EXISTS idx_posts_author ON posts(author_id);
CREATE INDEX IF NOT EXISTS idx_posts_feed   ON posts(status, visibility, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_link_reports_link ON link_reports(link_id);
