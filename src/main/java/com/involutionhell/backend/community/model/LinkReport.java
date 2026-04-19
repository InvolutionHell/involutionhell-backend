package com.involutionhell.backend.community.model;

import java.time.Instant;

/**
 * 对分享链接的举报记录。
 *
 * (linkId, reporterId) 组合唯一，同一人对同一条链接只能举报一次。
 * 3 条独立举报时 Service 层会把对应 SharedLink 转 PENDING_MANUAL 下架复审。
 */
public record LinkReport(
        Long id,
        Long linkId,
        Long reporterId,
        String reason,
        Instant createdAt
) {
}
