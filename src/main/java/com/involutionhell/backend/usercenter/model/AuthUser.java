package com.involutionhell.backend.usercenter.model;

import java.time.OffsetDateTime;

/**
 * 对应 Prisma 管理的 users 表（Auth.js OAuth 用户）。
 * 与 Java 侧 Sa-Token 认证的 UserAccount（user_accounts 表）相互独立。
 *
 * <pre>
 * CREATE TABLE users (
 *   id            SERIAL PRIMARY KEY,
 *   name          VARCHAR(255),
 *   email         VARCHAR(255) UNIQUE,
 *   "emailVerified" TIMESTAMPTZ,
 *   image         TEXT
 * );
 * </pre>
 */
public record AuthUser(
        Integer id,
        String name,
        String email,
        OffsetDateTime emailVerified,
        String image
) {}
