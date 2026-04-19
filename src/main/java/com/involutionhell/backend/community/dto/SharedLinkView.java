package com.involutionhell.backend.community.dto;

import com.involutionhell.backend.community.model.SharedLink;

import java.time.Instant;

/**
 * 对外展示的链接视图。
 * - 不暴露 urlHash / flags（内部字段）
 * - status 暴露给"我提交的"页面；公开 /feed 只拿 APPROVED 时前端可不渲染
 */
public record SharedLinkView(
        Long id,
        Long submitterId,
        String url,
        String host,
        String recommendation,
        String ogTitle,
        String ogDescription,
        String ogCover,
        String ogSiteName,
        boolean ogFetchFailed,
        String category,
        String status,
        int reportCount,
        Instant archivedAt,
        Instant createdAt
) {
    public static SharedLinkView from(SharedLink s) {
        return new SharedLinkView(
                s.id(),
                s.submitterId(),
                s.url(),
                s.host(),
                s.recommendation(),
                s.ogTitle(),
                s.ogDescription(),
                s.ogCover(),
                s.ogSiteName(),
                s.ogFetchError() != null && !s.ogFetchError().isEmpty(),
                s.category(),
                s.status(),
                s.reportCount(),
                s.archivedAt(),
                s.createdAt()
        );
    }
}
