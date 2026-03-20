-- Init DB script for local development

-- backend/src/main/resources/schema.sql
CREATE TABLE IF NOT EXISTS user_accounts (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    roles         TEXT         NOT NULL DEFAULT '',
    permissions   TEXT         NOT NULL DEFAULT ''
);

-- Default seeds for user_accounts
-- admin / Admin@123456
-- alice / Alice@123456
-- auditor / Audit@123456
INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
VALUES ('admin',   'ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d', 'Admin',   TRUE, 'admin',   'user:profile:read,user:center:read,user:center:manage'),
       ('alice',   'b02bb998ecc1616148b9b4ba0405dbd4c224acd1bac059d59f0a07b3b1a68400', 'Alice',   TRUE, 'user',    'user:profile:read'),
       ('auditor', 'ccabaaba054fb98905b5b9ee47174f57cb6088e04b1526f08b872dc06eaa6bb9', 'Auditor', TRUE, 'auditor', 'user:profile:read,user:center:read')
ON CONFLICT (username) DO NOTHING;

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
