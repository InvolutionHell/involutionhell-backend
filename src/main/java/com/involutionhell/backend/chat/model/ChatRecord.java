package com.involutionhell.backend.chat.model;

import java.time.OffsetDateTime;

/**
 * 对应 Prisma 管理的 "Chat" 表（注意：PostgreSQL 中表名大小写敏感，需加引号）。
 *
 * <pre>
 * CREATE TABLE "Chat" (
 *   id          TEXT PRIMARY KEY,
 *   "userId"    INT,
 *   "createdAt" TIMESTAMPTZ,
 *   "updatedAt" TIMESTAMPTZ
 * );
 * </pre>
 */
public record ChatRecord(
        String id,
        Integer userId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
