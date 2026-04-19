package com.involutionhell.backend.community.model;

/**
 * 链接审核状态常量。用 String 不用 enum：
 * - DB 层是 VARCHAR，新增状态不需要改 enum 代码
 * - 前端 JSON 通信时直接是字符串，避免 enum 序列化歧义
 */
public final class SharedLinkStatus {

    /** 刚提交，异步 worker 未处理。前端"我提交的"可见，主流不可见。 */
    public static final String PENDING = "PENDING";

    /** 白名单域名 + AI 判定安全，公开展示。 */
    public static final String APPROVED = "APPROVED";

    /** 非白名单域名，进人工待审队列。 */
    public static final String PENDING_MANUAL = "PENDING_MANUAL";

    /** AI 判定 nsfw / ad / flame 任一命中，进人工待审队列。 */
    public static final String FLAGGED = "FLAGGED";

    /** 人工拒绝，不再展示也不进任何队列。 */
    public static final String REJECTED = "REJECTED";

    /** 原文失效（HEAD 探活连续 2 次失败），主流不展示但作者可见，避免误以为静默失败。 */
    public static final String ARCHIVED = "ARCHIVED";

    private SharedLinkStatus() {}
}
