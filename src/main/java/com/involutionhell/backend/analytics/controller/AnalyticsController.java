package com.involutionhell.backend.analytics.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.analytics.dto.EventIngestDto;
import com.involutionhell.backend.analytics.dto.EventSummaryDto;
import com.involutionhell.backend.analytics.dto.TopDocDto;
import com.involutionhell.backend.analytics.service.AnalyticsEventIngestService;
import com.involutionhell.backend.analytics.service.AnalyticsService;
import com.involutionhell.backend.analytics.service.EventSummaryService;
import com.involutionhell.backend.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final EventSummaryService eventSummaryService;
    private final AnalyticsEventIngestService eventIngestService;

    public AnalyticsController(AnalyticsService analyticsService,
                               EventSummaryService eventSummaryService,
                               AnalyticsEventIngestService eventIngestService) {
        this.analyticsService = analyticsService;
        this.eventSummaryService = eventSummaryService;
        this.eventIngestService = eventIngestService;
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

    /**
     * 浏览器埋点写入入口：接收前端 trackEvent 上报的事件并落库。
     * SaToken 白名单开放（允许匿名访问），登录用户通过 satoken header 识别 userId。
     *
     * 失败策略：即使 DB 挂了也返回 ok，埋点绝不能阻塞用户主流程。
     * 错误只写日志，不对外暴露内部异常。
     */
    @PostMapping("/events")
    public ApiResponse<Void> ingestEvent(@RequestBody EventIngestDto body) {
        if (body == null || body.eventType() == null || body.eventType().isBlank()) {
            return ApiResponse.fail("eventType is required");
        }

        // 尝试从 SaToken 上下文读取当前登录用户。白名单路由不会自动 checkLogin，
        // 所以这里用 isLogin + getLoginIdAsLong 兼容匿名。
        Long userId = null;
        try {
            if (StpUtil.isLogin()) {
                userId = StpUtil.getLoginIdAsLong();
            }
        } catch (Exception ignored) {
            // token 过期 / 非法格式都按匿名处理
        }

        try {
            eventIngestService.insert(userId, body.eventType(), body.eventData());
        } catch (Exception e) {
            // 埋点是尽力而为，DB 异常只记日志，给前端 ok 避免触发客户端重试风暴
            log.warn("analytics event ingest failed: eventType={}, userId={}, err={}",
                    body.eventType(), userId, e.getMessage());
        }

        return ApiResponse.ok(null);
    }
}
