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

-- 站点维护者（GitHub OAuth 登录后由 sync 服务补 github_id；此处按 username 打 admin role）
-- 生产 Neon 上这些账号是 GitHub OAuth 登录后由 AuthService 自动创建的，所以用
-- ON CONFLICT DO UPDATE 幂等升级角色，避免漏 seed。
INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
VALUES ('longsizhuo', '', 'Siz Long', TRUE, 'admin,user', 'user:profile:read,user:center:read,user:center:manage'),
       ('Mira190',    '', 'Mira',     TRUE, 'admin,user', 'user:profile:read,user:center:read,user:center:manage'),
       ('Crokily',    '', 'Crokily',  TRUE, 'admin,user', 'user:profile:read,user:center:read,user:center:manage')
ON CONFLICT (username) DO UPDATE
    SET roles       = 'admin,user',
        permissions = 'user:profile:read,user:center:read,user:center:manage';

-- =============================================================================
-- Events（活动）相关表
-- =============================================================================
-- 背景：站点有 Coffee Chat / Mock Interview / Career Journey / Open.Onion 等社群活动。
-- 原先维护在前端 data/event.json，新增/改时间都要改代码发版，迁到后端让管理员自助编辑。

-- 活动主表
CREATE TABLE IF NOT EXISTS events (
    id             BIGSERIAL    PRIMARY KEY,
    title          VARCHAR(255) NOT NULL,
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

-- 种子：原来 data/event.json 里的 4 条活动（startTime 不填，先只保留元信息；
-- 管理员登录后在 /admin/events 里补时间再 publish）
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
ON CONFLICT DO NOTHING;
