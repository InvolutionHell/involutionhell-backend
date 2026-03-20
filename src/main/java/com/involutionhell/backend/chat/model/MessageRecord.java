package com.involutionhell.backend.chat.model;

import java.time.OffsetDateTime;

/**
 * 对应 Prisma 管理的 "Message" 表（注意：PostgreSQL 中表名大小写敏感，需加引号）。
 *
 * <pre>
 * CREATE TABLE "Message" (
 *   id          TEXT PRIMARY KEY,
 *   "chatId"    TEXT,
 *   role        TEXT,
 *   content     TEXT,
 *   "createdAt" TIMESTAMPTZ
 * );
 * </pre>
 */
public record MessageRecord(
        String id,
        String chatId,
        String role,
        String content,
        OffsetDateTime createdAt
) {}
