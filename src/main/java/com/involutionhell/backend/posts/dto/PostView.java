package com.involutionhell.backend.posts.dto;

import com.involutionhell.backend.posts.model.Post;

import java.time.Instant;
import java.util.List;

/**
 * 文章详情视图（详情页 / 分享页使用）。
 *
 * 包含完整 contentMd；作者信息由 authorUsername / authorDisplayName / authorAvatar 冗余，
 * 避免列表/详情分开查两张表。
 *
 * 不暴露 authorId（内部 ID，前端用 username 路由即可）。
 */
public record PostView(
        Long         id,
        String       slug,
        String       title,
        String       description,
        List<String> tags,
        String       contentMd,
        String       coverUrl,
        String       visibility,
        String       status,
        String       promotedPrUrl,
        Instant      promotedAt,
        int          viewCount,
        Instant      createdAt,
        Instant      updatedAt,
        // 作者冗余字段，前端无需再查 /api/user-center/profile
        String       authorUsername,
        String       authorDisplayName,
        String       authorAvatar
) {
    /** 从领域对象 + 作者信息组装视图。 */
    public static PostView from(Post p,
                                String authorUsername,
                                String authorDisplayName,
                                String authorAvatar) {
        return new PostView(
                p.id(),
                p.slug(),
                p.title(),
                p.description(),
                p.tags(),
                p.contentMd(),
                p.coverUrl(),
                p.visibility(),
                p.status(),
                p.promotedPrUrl(),
                p.promotedAt(),
                p.viewCount(),
                p.createdAt(),
                p.updatedAt(),
                authorUsername,
                authorDisplayName,
                authorAvatar
        );
    }
}
