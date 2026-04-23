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
MERGE INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
    KEY (username)
    VALUES ('admin', 'ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d', 'Admin', TRUE, 'admin', 'user:profile:read,user:center:read,user:center:manage');
MERGE INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
    KEY (username)
    VALUES ('alice', 'b02bb998ecc1616148b9b4ba0405dbd4c224acd1bac059d59f0a07b3b1a68400', 'Alice', TRUE, 'user', 'user:profile:read');
MERGE INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
    KEY (username)
    VALUES ('auditor', 'ccabaaba054fb98905b5b9ee47174f57cb6088e04b1526f08b872dc06eaa6bb9', 'Auditor', TRUE, 'auditor', 'user:profile:read,user:center:read');

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
