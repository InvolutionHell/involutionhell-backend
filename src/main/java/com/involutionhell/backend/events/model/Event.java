package com.involutionhell.backend.events.model;

import java.time.Instant;
import java.util.List;

/**
 * 社区活动（Coffee Chat / Mock Interview / Career Journey / Open.Onion 等）。
 *
 * 对应数据库表 events。字段语义：
 * - startTime / endTime：可为 null（未排期 / 进行中 / 已归档）
 * - status：draft（仅 admin 可见）/ published（公开）/ archived（历史）/ cancelled
 * - speakers：JSON 数组 [{name, avatarUrl, profileUrl}]，暂不做 user_accounts 关联外键，
 *   嘉宾可能不是站点注册用户
 * - tags：逗号分隔字符串，和 user_accounts.roles 同风格；未来按 tag 过滤时可以加 GIN 索引升 TEXT[]
 * - organizerId：可空。指向 user_accounts.id，组织方（一般是 admin 自己）
 */
public record Event(
        Long id,
        String title,
        String description,
        String coverUrl,
        Instant startTime,
        Instant endTime,
        String discordLink,
        String playbackUrl,
        List<Speaker> speakers,
        String tags,
        String status,
        Long organizerId,
        Instant createdAt,
        Instant updatedAt
) {
    /** 嘉宾信息。保持简单的 POJO 结构，避免和 user_accounts 耦合。 */
    public record Speaker(String name, String avatarUrl, String profileUrl) {}

    /** 判断活动是否处于"进行中"状态：published 且 startTime 已到 endTime 未过。 */
    public boolean isOngoing() {
        if (!"published".equals(status)) return false;
        Instant now = Instant.now();
        if (startTime != null && startTime.isAfter(now)) return false;
        if (endTime != null && endTime.isBefore(now)) return false;
        return true;
    }

    /** 判断活动是否已结束（archived 或 endTime 已过）。前端分区"历史活动"用这个判定。 */
    public boolean isPast() {
        if ("archived".equals(status) || "cancelled".equals(status)) return true;
        return endTime != null && endTime.isBefore(Instant.now());
    }
}
