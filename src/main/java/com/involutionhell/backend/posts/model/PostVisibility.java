package com.involutionhell.backend.posts.model;

/**
 * 文章可见性枚举常量。
 *
 * PUBLIC   - 公开，任何人可访问（但 noindex，不进搜索引擎）
 * UNLISTED - 仅凭链接访问（预留，MVP 阶段暂不暴露给前端）
 */
public final class PostVisibility {

    /** 公开 */
    public static final String PUBLIC   = "PUBLIC";

    /** 不列出（仅凭链接访问，预留） */
    public static final String UNLISTED = "UNLISTED";

    private PostVisibility() {}
}
