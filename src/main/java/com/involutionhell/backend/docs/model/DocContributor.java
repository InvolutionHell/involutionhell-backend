package com.involutionhell.backend.docs.model;

import java.time.OffsetDateTime;

/**
 * 对应 Prisma 管理的 doc_contributors 表（联合主键 doc_id + github_id）。
 *
 * <pre>
 * CREATE TABLE doc_contributors (
 *   doc_id              TEXT,
 *   github_id           BIGINT,
 *   contributions       INT DEFAULT 1,
 *   last_contributed_at TIMESTAMPTZ,
 *   created_at          TIMESTAMPTZ,
 *   updated_at          TIMESTAMPTZ,
 *   PRIMARY KEY (doc_id, github_id)
 * );
 * </pre>
 */
public record DocContributor(
        String docId,
        Long githubId,
        Integer contributions,
        OffsetDateTime lastContributedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
