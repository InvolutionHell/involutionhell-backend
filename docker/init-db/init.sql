-- Init DB script for local development

-- backend/src/main/resources/schema.sql
-- 列必须与 schema.sql 的 user_accounts 完全一致：init.sql 只在数据卷首次创建时
-- 跑一次，schema.sql 的 CREATE TABLE IF NOT EXISTS 对已存在的表是 no-op、补不上
-- 缺列。这里少列会让 github 登录（INSERT 列出 avatar_url/email/github_id/
-- preferences）和 user_identities 回填（读 github_id）在全新库上直接失败。
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
    github_id     BIGINT       UNIQUE,
    preferences   JSONB        NOT NULL DEFAULT '{}'::jsonb
);

-- Default seeds for user_accounts
-- admin / Admin@123456
-- alice / Alice@123456
-- auditor / Audit@123456
-- 哈希格式：bcrypt(cost=10) —— INV-003 起点
INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
VALUES ('admin',   '$2b$10$Bfnw8v.BsXeZVPbre94sJeokgEfKuCLsdH7ckJxzxn5nxirHJHmP.', 'Admin',   TRUE, 'admin',   'user:profile:read,user:center:read,user:center:manage'),
       ('alice',   '$2b$10$BmtVOmPK8Os/xreOTImsdec1fAA8Y9iTm1D823swYucSI2NDFdk.q', 'Alice',   TRUE, 'user',    'user:profile:read'),
       ('auditor', '$2b$10$/1OfzhrA6CITrjJsDbzk.uMLq6cHa/iOP./wL2BAPo9t7QRq7Ca5W', 'Auditor', TRUE, 'auditor', 'user:profile:read,user:center:read')
ON CONFLICT (username) DO NOTHING;

-- 关注关系（user_follows）—— 与 schema.sql 保持一致
CREATE TABLE IF NOT EXISTS user_follows (
    follower_id BIGINT      NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    followee_id BIGINT      NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX IF NOT EXISTS idx_user_follows_followee
    ON user_follows(followee_id, created_at DESC);

-- 登录身份（user_identities）—— 与 schema.sql 保持一致
-- 不含 schema.sql 里的 github_id 回填：全新库的种子账号（admin/alice/auditor）都无
-- github_id，回填是 0 行；真有存量时 schema.sql 会在启动（mode=always）时回填。
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

-- Prisma tables (frontend/prisma/schema.prisma)

-- users table
CREATE TABLE IF NOT EXISTS "users" (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255) UNIQUE,
    "emailVerified" TIMESTAMPTZ(6),
    image TEXT
);

-- accounts table
CREATE TABLE IF NOT EXISTS "accounts" (
    id SERIAL PRIMARY KEY,
    "userId" INTEGER NOT NULL REFERENCES "users"(id) ON DELETE CASCADE,
    type VARCHAR(255) NOT NULL,
    provider VARCHAR(255) NOT NULL,
    "providerAccountId" VARCHAR(255) NOT NULL,
    refresh_token TEXT,
    access_token TEXT,
    expires_at BIGINT,
    id_token TEXT,
    scope TEXT,
    session_state TEXT,
    token_type TEXT,
    UNIQUE(provider, "providerAccountId")
);

-- sessions table
CREATE TABLE IF NOT EXISTS "sessions" (
    id SERIAL PRIMARY KEY,
    "userId" INTEGER NOT NULL REFERENCES "users"(id) ON DELETE CASCADE,
    expires TIMESTAMPTZ(6) NOT NULL,
    "sessionToken" VARCHAR(255) UNIQUE NOT NULL
);

-- docs table
CREATE TABLE IF NOT EXISTS "docs" (
    id TEXT PRIMARY KEY,
    path_current TEXT,
    title TEXT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    contributor_stats JSONB DEFAULT '{}'::jsonb
);

-- doc_contributors table
CREATE TABLE IF NOT EXISTS "doc_contributors" (
    doc_id TEXT NOT NULL REFERENCES "docs"(id) ON DELETE CASCADE,
    github_id BIGINT NOT NULL,
    contributions INTEGER NOT NULL DEFAULT 1,
    last_contributed_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_id, github_id)
);

-- doc_paths table
CREATE TABLE IF NOT EXISTS "doc_paths" (
    doc_id TEXT NOT NULL REFERENCES "docs"(id) ON DELETE CASCADE,
    path TEXT NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
    PRIMARY KEY (doc_id, path)
);

-- Chat table
CREATE TABLE IF NOT EXISTS "Chat" (
    id TEXT PRIMARY KEY,
    "userId" INTEGER REFERENCES "users"(id),
    "createdAt" TIMESTAMP NOT NULL DEFAULT now(),
    "updatedAt" TIMESTAMP NOT NULL DEFAULT now()
);

-- Message table
CREATE TABLE IF NOT EXISTS "Message" (
    id TEXT PRIMARY KEY,
    "chatId" TEXT NOT NULL REFERENCES "Chat"(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    "createdAt" TIMESTAMP NOT NULL DEFAULT now()
);

-- AnalyticsEvent table
CREATE TABLE IF NOT EXISTS "AnalyticsEvent" (
    id TEXT PRIMARY KEY,
    "userId" INTEGER REFERENCES "users"(id),
    "eventType" TEXT NOT NULL,
    "eventData" JSONB,
    "createdAt" TIMESTAMP NOT NULL DEFAULT now()
);

-- Seeds for key tables
INSERT INTO "users" (id, name, email) VALUES (1, '测试用户', 'test@involutionhell.com') ON CONFLICT DO NOTHING;
INSERT INTO "docs" (id, title, path_current) 
VALUES ('getting-started', '内卷地狱：从入门到入土', '/docs/getting-started'),
       ('java-guide', 'Java 25 新特性指南', '/docs/java-25')
ON CONFLICT DO NOTHING;

INSERT INTO "doc_contributors" (doc_id, github_id, contributions)
VALUES ('getting-started', 10001, 42),
       ('java-guide', 10001, 10)
ON CONFLICT DO NOTHING;

INSERT INTO "Chat" (id, "userId") VALUES ('chat-1', 1) ON CONFLICT DO NOTHING;
INSERT INTO "Message" (id, "chatId", role, content) 
VALUES ('msg-1', 'chat-1', 'user', '如何避免内卷？'),
       ('msg-2', 'chat-1', 'assistant', '最好的办法是加入内卷地狱，把它变成我们的天堂。')
ON CONFLICT DO NOTHING;
