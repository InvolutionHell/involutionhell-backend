package com.involutionhell.backend.posts.model;

import java.time.Instant;
import java.util.List;

/**
 * 用户原创文章领域对象，对应数据库表 posts。
 *
 * 设计取向：
 * - 与 Fumadocs(/docs) 体系完全隔离：posts 直接落库，不走 Git PR
 * - 与 shared_links 解耦：shared_links 是外部 URL，posts 是站内 markdown 长文
 * - slug 在同一作者下唯一（author_id + slug UNIQUE），构成 /u/{username}/posts/{slug}
 * - tags 存 JSONB 字符串数组，查询时由 JdbcPostRepository 反序列化为 List<String>
 * - visibility / status 用 PostVisibility / PostStatus 常量，避免魔法字符串散落
 * - promotedPrUrl：文章"转正"后记录 GitHub PR 链接，并同步写 promotedAt 时间戳
 */
public record Post(
        Long    id,
        Long    authorId,
        String  slug,
        String  title,
        String  description,
        List<String> tags,
        String  contentMd,
        String  coverUrl,
        String  visibility,      // PostVisibility 常量
        String  status,          // PostStatus 常量
        String  promotedPrUrl,
        Instant promotedAt,
        int     viewCount,
        Instant createdAt,
        Instant updatedAt
) {}
