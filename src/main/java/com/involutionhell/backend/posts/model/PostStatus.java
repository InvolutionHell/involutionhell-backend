package com.involutionhell.backend.posts.model;

/**
 * 文章发布状态枚举常量。
 *
 * DRAFT     - 草稿，作者未发布（预留，MVP 阶段暂不开放，前端不传此值）
 * PUBLISHED - 已发布，对外可见
 */
public final class PostStatus {

    /** 草稿（预留） */
    public static final String DRAFT     = "DRAFT";

    /** 已发布 */
    public static final String PUBLISHED = "PUBLISHED";

    private PostStatus() {}
}
