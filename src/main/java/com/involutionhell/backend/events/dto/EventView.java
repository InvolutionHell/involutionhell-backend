package com.involutionhell.backend.events.dto;

import com.involutionhell.backend.events.model.Event;

import java.time.Instant;
import java.util.List;

/**
 * 对外暴露的活动视图。目前字段和 Event 记录一一对应，独立 DTO 是为了：
 * 1. 将来加"是否当前用户已感兴趣 / 是否进行中"这种衍生字段时有地方放
 * 2. 避免未来给 Event 加管理字段（比如 internal_notes）时意外暴露到公开 API
 */
public record EventView(
        Long id,
        String title,
        String description,
        String coverUrl,
        Instant startTime,
        Instant endTime,
        String discordLink,
        String playbackUrl,
        List<Event.Speaker> speakers,
        List<String> tags,
        String status,
        Long organizerId,
        Instant createdAt,
        Instant updatedAt,
        long interestCount,
        boolean ongoing,
        boolean past
) {
    /** 从 Event + 动态统计数据构造 View。interestCount 由调用方传入（需要额外查 DB）。 */
    public static EventView from(Event event, long interestCount) {
        return new EventView(
                event.id(),
                event.title(),
                event.description(),
                event.coverUrl(),
                event.startTime(),
                event.endTime(),
                event.discordLink(),
                event.playbackUrl(),
                event.speakers(),
                splitTags(event.tags()),
                event.status(),
                event.organizerId(),
                event.createdAt(),
                event.updatedAt(),
                interestCount,
                event.isOngoing(),
                event.isPast()
        );
    }

    /** "interview,mock" → ["interview", "mock"]；空串 → 空列表 */
    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return List.of(tags.split("\\s*,\\s*"));
    }
}
