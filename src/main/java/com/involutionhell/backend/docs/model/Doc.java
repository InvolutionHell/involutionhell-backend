package com.involutionhell.backend.docs.model;

import java.time.OffsetDateTime;

/**
 * 对应 Prisma 管理的 docs 表。
 * contributor_stats 为 JSONB 列，此处以原始字符串持有，由调用方按需解析。
 *
 * <pre>
 * CREATE TABLE docs (
 *   id                TEXT PRIMARY KEY,
 *   path_current      TEXT,
 *   title             TEXT,
 *   contributor_stats JSONB DEFAULT '{}',
 *   created_at        TIMESTAMPTZ,
 *   updated_at        TIMESTAMPTZ
 * );
 * </pre>
 */
public record Doc(
        String id,
        String pathCurrent,
        String title,
        String contributorStats,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
