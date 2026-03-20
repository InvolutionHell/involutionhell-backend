package com.involutionhell.backend.docs.model;

import java.time.OffsetDateTime;

/**
 * 对应 Prisma 管理的 doc_paths 表（联合主键 doc_id + path）。
 * 记录文档的历史路径，用于重定向。
 *
 * <pre>
 * CREATE TABLE doc_paths (
 *   doc_id     TEXT,
 *   path       TEXT,
 *   created_at TIMESTAMPTZ,
 *   updated_at TIMESTAMPTZ,
 *   PRIMARY KEY (doc_id, path)
 * );
 * </pre>
 */
public record DocPath(
        String docId,
        String path,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
