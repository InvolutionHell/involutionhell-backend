package com.involutionhell.backend.analytics.dto;

/**
 * 热门文档榜返回给前端的一行数据。
 *
 * @param path  文档 URL 路径，例如 /docs/ai/multimodal/qwenvl（与前端路由保持一致，可直接用作 Link href）
 * @param title 文档标题，来自 docs 表；过滤逻辑保证不会为 null（详见 AnalyticsService.getTopDocs）
 * @param views 访问量，对应 GA4 screenPageViews 指标
 */
public record TopDocDto(String path, String title, long views) {}
