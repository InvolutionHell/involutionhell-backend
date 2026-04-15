package com.involutionhell.backend.analytics.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * 埋点事件写入服务：把前端 / 浏览器上报的事件持久化到 Prisma 维护的 "AnalyticsEvent" 表。
 *
 * 该表原来由 Next.js 的 /api/analytics 路由直写，迁移到后端是为了：
 *  1. 让 Next 不再占用 Vercel Fluid CPU 做转发
 *  2. 复用 Java 的连接池，常驻进程写入比 serverless 冷启更快
 *
 * 目前 top-docs 排行榜读的是 GA4，不读这张表；但保留表有两个用途：
 *  a. 未来自建 analytics dashboard 时直接复用
 *  b. SaToken 登录态下可精确追踪单用户行为（GA4 是匿名）
 */
@Service
public class AnalyticsEventIngestService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsEventIngestService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条埋点记录。id 为 UUID（兼容 Prisma 的 TEXT PK 设计），createdAt 交给 DB NOW()，
     * eventData 作为 jsonb 写入。任何异常（JSON 序列化失败 / DB 异常）由调用方 controller 统一吞掉，
     * 埋点绝不能影响主流程。
     *
     * @param userId    登录用户 id，匿名为 null；类型与 user_accounts.id 对齐 (BigInt/Long)
     * @param eventType 事件类型标识，长度限制由 DB 自身约束
     * @param eventData 事件上下文，null 或空都会被序列化为 "{}"
     */
    public void insert(Long userId, String eventType, Map<String, Object> eventData) {
        String id = UUID.randomUUID().toString();
        String json;
        try {
            json = objectMapper.writeValueAsString(eventData != null ? eventData : Map.of());
        } catch (Exception e) {
            // Jackson 3 的 writeValueAsString 会抛 RuntimeException；
            // 序列化失败降级为空对象，保证事件类型/用户维度仍可统计
            json = "{}";
        }
        jdbcTemplate.update(
                """
                INSERT INTO "AnalyticsEvent" (id, "userId", "eventType", "eventData", "createdAt")
                VALUES (?, ?, ?, ?::jsonb, NOW())
                """,
                id, userId, eventType, json
        );
    }
}
