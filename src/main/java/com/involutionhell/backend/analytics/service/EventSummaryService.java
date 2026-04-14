package com.involutionhell.backend.analytics.service;

import com.involutionhell.backend.analytics.dto.EventSummaryDto;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事件聚合服务，只读查询 AnalyticsEvent 表。
 * 注意：Prisma 生成的表名和字段名均为 PascalCase/camelCase，
 * PostgreSQL 大小写敏感，必须加双引号。
 */
@Service
public class EventSummaryService {

    // window 参数映射为 PostgreSQL interval 字符串
    private static final String INTERVAL_7D  = "7 days";
    private static final String INTERVAL_30D = "30 days";
    // "all" 时不加 WHERE 时间条件，直接查全量

    private final JdbcTemplate jdbcTemplate;

    public EventSummaryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按时间窗口聚合各 eventType 的总数和独立用户数。
     * TTL 5 分钟由 Caffeine 配置控制。
     *
     * 缓存 key 用规范化后的 window（7d/30d/all），原因：
     *   1. 非法 window（如 "xyz"、带空格等）和 null 不会在缓存里产生额外 key，
     *      避免公开接口被用来污染缓存占位。
     *   2. Caffeine 不接受 null key，直接用 #window 传 null 会在 SpEL 阶段抛异常。
     *
     * @param window 原始入参，"7d" / "30d" / "all"，非法值回退到 "30d"
     */
    @Cacheable(value = "eventSummary", key = "T(com.involutionhell.backend.analytics.service.EventSummaryService).normalize(#window)")
    public List<EventSummaryDto> summarize(String window) {
        String normalizedWindow = normalize(window);

        if ("all".equals(normalizedWindow)) {
            return jdbcTemplate.query(
                    """
                    SELECT "eventType",
                           count(*) AS total,
                           count(DISTINCT "userId") AS unique_users
                    FROM "AnalyticsEvent"
                    GROUP BY "eventType"
                    ORDER BY total DESC
                    """,
                    (rs, rowNum) -> new EventSummaryDto(
                            rs.getString("eventType"),
                            rs.getLong("total"),
                            rs.getLong("unique_users")
                    )
            );
        }

        // 有时间窗口：用 JDBC 占位符传 interval 值，防止 SQL 注入
        String interval = "7d".equals(normalizedWindow) ? INTERVAL_7D : INTERVAL_30D;
        return jdbcTemplate.query(
                """
                SELECT "eventType",
                       count(*) AS total,
                       count(DISTINCT "userId") AS unique_users
                FROM "AnalyticsEvent"
                WHERE "createdAt" > now() - ?::interval
                GROUP BY "eventType"
                ORDER BY total DESC
                """,
                (rs, rowNum) -> new EventSummaryDto(
                        rs.getString("eventType"),
                        rs.getLong("total"),
                        rs.getLong("unique_users")
                ),
                interval
        );
    }

    /**
     * 将用户传入的 window 字符串规范化。
     * 非法值（null 或未知字符串）回退到默认值 "30d"。
     *
     * 声明为 public static 以便 @Cacheable 的 SpEL 表达式可以调用（T(...) 语法）。
     */
    public static String normalize(String window) {
        if ("7d".equals(window) || "all".equals(window)) {
            return window;
        }
        return "30d";
    }
}
