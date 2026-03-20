package com.involutionhell.backend.usercenter.model;

/**
 * 对应 Prisma 管理的 accounts 表（Auth.js OAuth 关联账号）。
 *
 * <pre>
 * CREATE TABLE accounts (
 *   id                  SERIAL PRIMARY KEY,
 *   "userId"            INT,
 *   type                VARCHAR(255),
 *   provider            VARCHAR(255),
 *   "providerAccountId" VARCHAR(255),
 *   refresh_token       TEXT,
 *   access_token        TEXT,
 *   expires_at          BIGINT,
 *   id_token            TEXT,
 *   scope               TEXT,
 *   session_state       TEXT,
 *   token_type          TEXT
 * );
 * </pre>
 */
public record OAuthAccount(
        Integer id,
        Integer userId,
        String type,
        String provider,
        String providerAccountId,
        String refreshToken,
        String accessToken,
        Long expiresAt,
        String idToken,
        String scope,
        String sessionState,
        String tokenType
) {}
