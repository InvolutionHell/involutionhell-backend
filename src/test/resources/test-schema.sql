-- 测试环境 H2（PostgreSQL MODE）建表 + 种子数据
CREATE TABLE IF NOT EXISTS user_accounts (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(255),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    roles         TEXT         NOT NULL DEFAULT '',
    permissions   TEXT         NOT NULL DEFAULT ''
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
