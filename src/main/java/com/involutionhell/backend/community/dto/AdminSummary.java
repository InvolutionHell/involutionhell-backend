package com.involutionhell.backend.community.dto;

import java.util.List;

/**
 * 审核摘要（/api/community/links/internal/summary）。
 *
 * 给 ChatBot 每日 digest 用：
 * - pendingManual / flagged：当前待审队列累计量
 * - approvedLast24h：过去 24 小时自动通过的量（看渠道吞吐）
 * - pendingSamples：PENDING_MANUAL 队列里最早的 N 条，帮管理员快速判断该不该去后台
 *
 * 这个 DTO 不含密钥/用户名等敏感字段，纯展示用。
 */
public record AdminSummary(
        int pendingManual,
        int flagged,
        int approvedLast24h,
        List<Sample> pendingSamples
) {
    public record Sample(Long id, String host, String url) {}
}
