package com.involutionhell.backend.posts.dto;

import com.involutionhell.backend.posts.model.Post;

import java.time.Instant;
import java.util.List;

/**
 * 文章列表摘要视图（/feed 原创 Tab 和 /u/{username}/posts 列表页使用）。
 *
 * 不包含 contentMd（列表页只需摘要，避免传输过大）。
 * 作者信息冗余在此，前端卡片无需再发请求。
 */
public record PostSummaryView(
        Long         id,
        String       slug,
        String       title,
        String       description,
        List<String> tags,
        String       coverUrl,
        String       visibility,
        String       status,
        boolean      promoted,          // promotedPrUrl != null 即为已转正
        int          viewCount,
        Instant      createdAt,
        // 作者冗余字段
        String       authorUsername,
        String       authorDisplayName,
        String       authorAvatar
) {
    /** 从领域对象 + 作者信息组装摘要视图。 */
    public static PostSummaryView from(Post p,
                                       String authorUsername,
                                       String authorDisplayName,
                                       String authorAvatar) {
        return new PostSummaryView(
                p.id(),
                p.slug(),
                p.title(),
                p.description(),
                p.tags(),
                p.coverUrl(),
                p.visibility(),
                p.status(),
                p.promotedPrUrl() != null,
                p.viewCount(),
                p.createdAt(),
                authorUsername,
                authorDisplayName,
                authorAvatar
        );
    }
}
