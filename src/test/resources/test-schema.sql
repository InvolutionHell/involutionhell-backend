-- 测试环境 H2（PostgreSQL MODE）建表 + 种子数据
CREATE TABLE IF NOT EXISTS user_accounts (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    roles         TEXT         NOT NULL DEFAULT '',
    permissions   TEXT         NOT NULL DEFAULT '',
    avatar_url    VARCHAR(500),
    email         VARCHAR(255),
    github_id     BIGINT       UNIQUE,
    preferences   VARCHAR(4000) NOT NULL DEFAULT '{}'
);

-- 种子账号（与生产保持一致）逐行插入，H2 兼容写法
-- 哈希格式：bcrypt(cost=10) —— INV-003 起点；明文与生产一致 (Admin@123456 等)
MERGE INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
    KEY (username)
    VALUES ('admin', '$2b$10$Bfnw8v.BsXeZVPbre94sJeokgEfKuCLsdH7ckJxzxn5nxirHJHmP.', 'Admin', TRUE, 'admin', 'user:profile:read,user:center:read,user:center:manage');
MERGE INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
    KEY (username)
    VALUES ('alice', '$2b$10$BmtVOmPK8Os/xreOTImsdec1fAA8Y9iTm1D823swYucSI2NDFdk.q', 'Alice', TRUE, 'user', 'user:profile:read');
MERGE INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
    KEY (username)
    VALUES ('auditor', '$2b$10$/1OfzhrA6CITrjJsDbzk.uMLq6cHa/iOP./wL2BAPo9t7QRq7Ca5W', 'Auditor', TRUE, 'auditor', 'user:profile:read,user:center:read');

-- 登录身份表（与生产 schema.sql 的 user_identities 对应；TIMESTAMPTZ 用 TIMESTAMP 代替）。
-- 生产的启动回填（INSERT...SELECT...ON CONFLICT DO NOTHING）这里不放：种子账号无 github_id，
-- 回填幂等性由 UserIdentityRepositoryTests 在 H2 上直接执行该语句验证。
CREATE TABLE IF NOT EXISTS user_identities (
    id                   BIGSERIAL    PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    provider             VARCHAR(32)  NOT NULL CHECK (provider = lower(provider)),
    provider_user_id     VARCHAR(255) NOT NULL,
    email_at_link        VARCHAR(255),
    display_name_at_link VARCHAR(255),
    linked_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at        TIMESTAMP,
    UNIQUE (provider, provider_user_id),
    UNIQUE (user_id, provider)
);

-- Events 相关表（测试用 H2 语法）。JSONB 用 VARCHAR 代替，与 user_accounts.preferences 的策略一致
CREATE TABLE IF NOT EXISTS events (
    id             BIGSERIAL    PRIMARY KEY,
    title          VARCHAR(255) NOT NULL UNIQUE,
    description    TEXT         NOT NULL DEFAULT '',
    cover_url      VARCHAR(500),
    start_time     TIMESTAMP,
    end_time       TIMESTAMP,
    discord_link   VARCHAR(500),
    playback_url   VARCHAR(500),
    speakers       VARCHAR(4000) NOT NULL DEFAULT '[]',
    tags           TEXT         NOT NULL DEFAULT '',
    status         VARCHAR(20)  NOT NULL DEFAULT 'published',
    organizer_id   BIGINT,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 关注关系（user_follows）—— 与 schema.sql 保持一致；H2 PostgreSQL MODE 接受
CREATE TABLE IF NOT EXISTS user_follows (
    follower_id BIGINT    NOT NULL,
    followee_id BIGINT    NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id),
    FOREIGN KEY (follower_id) REFERENCES user_accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (followee_id) REFERENCES user_accounts(id) ON DELETE CASCADE
);
-- 与生产 schema 一致的"粉丝列表"二级索引；测试 schema 缺这个索引会让
-- "索引被误删"这种 drift 逃过 CI（即便表本身存在）
CREATE INDEX IF NOT EXISTS idx_user_follows_followee
    ON user_follows(followee_id, created_at DESC);

CREATE TABLE IF NOT EXISTS event_interests (
    event_id   BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, user_id),
    -- 补齐外键与 ON DELETE CASCADE 和生产 schema 对齐，否则 H2 测试既不能覆盖
    -- "删 event 级联清 interest" 这个关键路径，也可能插入不存在的 event/user 产生脏数据
    FOREIGN KEY (event_id) REFERENCES events(id)        ON DELETE CASCADE,
    FOREIGN KEY (user_id)  REFERENCES user_accounts(id) ON DELETE CASCADE
);

-- docs / doc_paths：给 AnalyticsService 的 UNION 查询用。
-- 这里只保留 analytics 关心的列；JSONB / TIMESTAMPTZ 等在 H2 里降级成 VARCHAR / TIMESTAMP。
CREATE TABLE IF NOT EXISTS docs (
    id               TEXT      PRIMARY KEY,
    path_current     TEXT,
    title            TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    contributor_stats VARCHAR(4000) NOT NULL DEFAULT '{}'
);

CREATE TABLE IF NOT EXISTS doc_paths (
    doc_id     TEXT      NOT NULL,
    path       TEXT      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (doc_id, path),
    FOREIGN KEY (doc_id) REFERENCES docs(id) ON DELETE CASCADE
);

-- ─── 社区分享（shared_links）测试 schema ──────────────────────────────────
-- 与生产 schema.sql 对齐，JSONB → VARCHAR，TIMESTAMPTZ → TIMESTAMP 降级
CREATE TABLE IF NOT EXISTS shared_links (
    id               BIGSERIAL    PRIMARY KEY,
    submitter_id     BIGINT       NOT NULL,
    url              TEXT         NOT NULL,
    url_hash         VARCHAR(64)  NOT NULL UNIQUE,
    host             VARCHAR(255) NOT NULL,
    recommendation   TEXT,
    og_title         TEXT,
    og_description   TEXT,
    og_cover         TEXT,
    og_site_name     VARCHAR(255),
    og_fetch_error   TEXT,
    category         VARCHAR(64),
    flags            VARCHAR(1000) NOT NULL DEFAULT '{}',
    status           VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    report_count     INT          NOT NULL DEFAULT 0,
    archived_at      TIMESTAMP,
    archived_reason  VARCHAR(64),
    admin_note       TEXT,
    probe_fail_count INT          NOT NULL DEFAULT 0,
    probe_last_at    TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (submitter_id) REFERENCES user_accounts(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS link_reports (
    id          BIGSERIAL    PRIMARY KEY,
    link_id     BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason      VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (link_id)     REFERENCES shared_links(id)    ON DELETE CASCADE,
    FOREIGN KEY (reporter_id) REFERENCES user_accounts(id)   ON DELETE CASCADE
);

-- seed discord-bridge 与生产 schema.sql 保持一致
MERGE INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
    KEY (username)
    VALUES ('discord-bridge', '!', 'Discord Bridge', FALSE, 'bridge', '');

-- 注：Chat / Message 表暂未在测试 schema 中建表，因为 H2 PostgreSQL MODE
-- 不支持 JdbcChatHistoryRepository 用的 INSERT ... ON CONFLICT (id) DO UPDATE
-- 语法。SecurityInvariantsTests 通过 @MockitoBean 替换 ChatHistoryRepository
-- 测试归属校验（INV-002），不需要真表。如果未来要做 SQL 层集成测试，建议
-- 引入 testcontainers PostgreSQL，而不是改 repository 兼容 H2。
