package com.involutionhell.backend.community.model;

import java.time.Instant;
import java.util.Map;

/**
 * 社区分享链接（用户随手转发的公众号 / 知乎 / 小红书等文章）。
 *
 * 对应数据库表 shared_links。与 Fumadocs 文档体系完全隔离：
 * - Fumadocs 走 Git PR，严肃、有版本
 * - shared_links 走 UGC + AI 异步审核，轻量、实时性不强
 *
 * 字段语义：
 * - urlHash：sha256(url)，用于去重（同一 URL 不同用户也只能有一条）
 * - host：规范化后的根域（严格精确匹配，防 weixin.qq.com.evil.com 钓鱼）
 * - flags：{nsfw, ad, flame} 三个 boolean，DeepSeek 返回的安全判定
 * - status：见 SharedLinkStatus
 * - ogFetchError：OG 抓取失败时记录原因，前端降级展示仍保留卡片
 * - archivedAt / archivedReason：原文失效（HEAD 探活连续 2 次失败）后写入
 */
public record SharedLink(
        Long id,
        Long submitterId,
        String url,
        String urlHash,
        String host,
        String recommendation,
        String ogTitle,
        String ogDescription,
        String ogCover,
        String ogSiteName,
        String ogFetchError,
        String category,
        Map<String, Boolean> flags,
        String status,
        int reportCount,
        Instant archivedAt,
        String archivedReason,
        Instant createdAt,
        Instant updatedAt
) {
}
