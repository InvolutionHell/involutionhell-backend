package com.involutionhell.backend.community.model;

import java.util.List;
import java.util.Set;

/**
 * 分类枚举 slug（存 DB 用，前端展示走 next-intl 翻译）。
 *
 * 8 个固定分类，AI 必须从这里选一个；temperature=0 + prompt 强约束枚举。
 * 增删分类需要：
 *   1. 改这里
 *   2. 改前端 messages/{zh,en}.json 的 feed.category.* 命名空间
 *   3. 考虑老数据如何迁移（删除分类需要批量 UPDATE 成 other）
 */
public final class LinkCategory {

    public static final String AI_FRONTIER   = "ai_frontier";    // AI 前沿 / 论文解读
    public static final String ENGINEERING   = "engineering";    // 工程实践 / 工具
    public static final String JOB           = "job";            // 求职 & 实习
    public static final String GRAD_STUDY    = "grad_study";     // 考研 & 留学
    public static final String INDUSTRY      = "industry";       // 行业观察 / 商业
    public static final String LEARNING      = "learning";       // 学习方法 / 认知
    public static final String LIFESTYLE     = "lifestyle";      // 生活 & 随笔
    public static final String OTHER         = "other";          // 其他（AI 不确定时的兜底）

    public static final List<String> ALL = List.of(
            AI_FRONTIER, ENGINEERING, JOB, GRAD_STUDY,
            INDUSTRY, LEARNING, LIFESTYLE, OTHER
    );

    private static final Set<String> ALL_SET = Set.copyOf(ALL);

    /** 校验 AI 返回的分类是否合法。非法时兜底为 OTHER。 */
    public static String normalize(String raw) {
        if (raw == null) return OTHER;
        String lower = raw.trim().toLowerCase();
        return ALL_SET.contains(lower) ? lower : OTHER;
    }

    private LinkCategory() {}
}
