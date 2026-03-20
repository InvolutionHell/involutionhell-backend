package com.involutionhell.backend.analytics.model;

import java.time.OffsetDateTime;

/**
 * 对应 Prisma 管理的 "AnalyticsEvent" 表（注意：PostgreSQL 中表名大小写敏感，需加引号）。
 * eventData 为 JSONB 列，此处以原始字符串持有，由调用方按需解析。
 *
 * <pre>
 * CREATE TABLE "AnalyticsEvent" (
 *   id          TEXT PRIMARY KEY,
 *   "userId"    INT,
 *   "eventType" TEXT,
 *   "eventData" JSONB,
 *   "createdAt" TIMESTAMPTZ
 * );
 * </pre>
 */
public record AnalyticsEvent(
        String id,
        Integer userId,
        String eventType,
        String eventData,
        OffsetDateTime createdAt
) {}
