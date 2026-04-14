package com.involutionhell.backend.analytics.controller;

import com.involutionhell.backend.analytics.dto.EventSummaryDto;
import com.involutionhell.backend.analytics.dto.TopDocDto;
import com.involutionhell.backend.analytics.service.AnalyticsService;
import com.involutionhell.backend.analytics.service.EventSummaryService;
import com.involutionhell.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Analytics 聚合接口，目前所有端点均为公开路由（SaTokenConfigure 白名单）。
 * 数据来自 GA4，匿名用户也可访问。
 */
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    /** 支持的时间窗口取值，其他值一律回退到 30d，避免奇怪参数打到 GA4 */
    private static final Set<String> VALID_WINDOWS = Set.of("7d", "30d", "all");

    private final AnalyticsService analyticsService;
    private final EventSummaryService eventSummaryService;

    public AnalyticsController(AnalyticsService analyticsService,
                               EventSummaryService eventSummaryService) {
        this.analyticsService = analyticsService;
        this.eventSummaryService = eventSummaryService;
    }

    /**
     * 热门文档排行榜：按访问量倒序返回最多 N 条真实文档。
     * 数据来自 GA4，结果 JOIN docs 表补中文标题并过滤掉无 docs 记录的路径（首页/父目录导航页等）。
     *
     * @param window 时间窗口：7d / 30d / all，非法值自动回退到 30d
     * @param limit  返回条数，范围 [1, 100]，超出会被夹到边界
     */
    @GetMapping("/top-docs")
    public ApiResponse<List<TopDocDto>> topDocs(
            @RequestParam(defaultValue = "30d") String window,
            @RequestParam(defaultValue = "20") int limit
    ) {
        // 防御性校验：枚举外的窗口参数回退到 30d，limit 夹到 [1, 100]
        if (!VALID_WINDOWS.contains(window)) {
            window = "30d";
        }
        limit = Math.min(Math.max(limit, 1), 100);

        List<TopDocDto> docs = analyticsService.getTopDocs(window, limit);
        return ApiResponse.ok(docs);
    }

    /**
     * 按时间窗口返回各事件类型的总数和独立用户数。
     *
     * @param window 7d | 30d | all，非法值回退到 30d
     */
    @GetMapping("/events/summary")
    public ApiResponse<List<EventSummaryDto>> eventsSummary(
            @RequestParam(defaultValue = "30d") String window) {
        if (!VALID_WINDOWS.contains(window)) {
            window = "30d";
        }
        return ApiResponse.ok(eventSummaryService.summarize(window));
    }
}
