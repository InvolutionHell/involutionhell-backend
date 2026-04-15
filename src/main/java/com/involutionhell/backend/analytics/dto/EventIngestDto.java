package com.involutionhell.backend.analytics.dto;

import java.util.Map;

/**
 * 前端浏览器直连后端写入埋点的请求体。
 *
 * @param eventType 事件类型，如 page_view / search_open / doc_share，必填
 * @param eventData 事件上下文（path / url / query 等），JSON 对象，可选；为 null 时按 {} 处理
 */
public record EventIngestDto(String eventType, Map<String, Object> eventData) {}
