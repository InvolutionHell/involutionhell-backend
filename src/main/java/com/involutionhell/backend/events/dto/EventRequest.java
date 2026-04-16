package com.involutionhell.backend.events.dto;

import com.involutionhell.backend.events.model.Event;
import com.involutionhell.backend.events.model.Event.Speaker;

import java.time.Instant;
import java.util.List;

/**
 * Admin 创建 / 更新活动的入参。
 *
 * 不直接用 Event 当入参是为了：
 * - 屏蔽客户端传 id / createdAt / updatedAt（这些必须由后端控制）
 * - 入参 tags 是 List<String> 更符合前端习惯；出参依然给 List<String>；DB 层转成逗号分隔
 * - 允许字段部分缺省（全都 String 可空），避免前端表单空字段触发 Jackson 报错
 */
public record EventRequest(
        String title,
        String description,
        String coverUrl,
        Instant startTime,
        Instant endTime,
        String discordLink,
        String playbackUrl,
        List<Speaker> speakers,
        List<String> tags,
        String status
) {
    /** 转换成 domain Event。id / timestamps / organizerId 由 Controller 层填。 */
    public Event toEvent(Long id, Long organizerId, Instant createdAt, Instant updatedAt) {
        return new Event(
                id,
                title != null ? title.trim() : "",
                description != null ? description : "",
                emptyToNull(coverUrl),
                startTime,
                endTime,
                emptyToNull(discordLink),
                emptyToNull(playbackUrl),
                speakers != null ? speakers : List.of(),
                joinTags(tags),
                status != null && !status.isBlank() ? status : "draft",
                organizerId,
                createdAt,
                updatedAt
        );
    }

    private static String emptyToNull(String s) {
        return s != null && !s.isBlank() ? s.trim() : null;
    }

    private static String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        return String.join(",", tags.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
