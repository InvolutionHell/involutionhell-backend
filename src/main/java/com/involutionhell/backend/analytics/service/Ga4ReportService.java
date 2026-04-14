package com.involutionhell.backend.analytics.service;

import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import com.google.analytics.data.v1beta.DateRange;
import com.google.analytics.data.v1beta.Dimension;
import com.google.analytics.data.v1beta.Metric;
import com.google.analytics.data.v1beta.Row;
import com.google.analytics.data.v1beta.RunReportRequest;
import com.google.analytics.data.v1beta.RunReportResponse;
import com.involutionhell.backend.analytics.config.Ga4Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * GA4 Data API 封装层。负责拼 runReport 请求并把结果转成内部 PathCount 列表，
 * 让上层 AnalyticsService 不依赖 google-analytics-data 的具体类型。
 * 注意：GA4 对当天数据有 24~48h 延迟，"today" 返回的数据通常不完整。
 */
@Service
public class Ga4ReportService {

    private static final Logger log = LoggerFactory.getLogger(Ga4ReportService.class);

    /** 路径 → 访问量，GA4 层面只关心 pagePath 和 screenPageViews */
    public record PathCount(String path, long views) {}

    private final BetaAnalyticsDataClient client;
    /** GA4 API 要求 property 资源名格式是 "properties/<id>" */
    private final String propertyId;

    public Ga4ReportService(BetaAnalyticsDataClient client, Ga4Properties ga4Properties) {
        this.client = client;
        this.propertyId = "properties/" + ga4Properties.getPropertyId();
    }

    /**
     * 按时间窗口拉取访问量最高的 N 条 pagePath。
     * 调用失败时抛 {@link Ga4UnavailableException}，由 GlobalExceptionHandler 转 503 返回前端，
     * 不把原始 gRPC 异常泄露给客户端。
     *
     * @param window 时间窗口：7d / 30d / all，其他值走 30d 默认
     * @param limit  最多返回条数，GA4 API 硬上限是 250000
     */
    public List<PathCount> fetchTopPaths(String window, int limit) {
        DateRange dateRange = buildDateRange(window);

        RunReportRequest request = RunReportRequest.newBuilder()
                .setProperty(propertyId)
                .addDimensions(Dimension.newBuilder().setName("pagePath"))
                .addMetrics(Metric.newBuilder().setName("screenPageViews"))
                .addDateRanges(dateRange)
                .setLimit(limit)
                .build();

        log.info("调用 GA4 API，property={}, window={}, limit={}", propertyId, window, limit);
        RunReportResponse response;
        try {
            response = client.runReport(request);
        } catch (Exception e) {
            log.error("GA4 API 调用失败: {}", e.getMessage(), e);
            throw new Ga4UnavailableException("GA4 数据服务暂时不可用", e);
        }

        List<PathCount> result = new ArrayList<>();
        for (Row row : response.getRowsList()) {
            String path = row.getDimensionValues(0).getValue();
            long views = Long.parseLong(row.getMetricValues(0).getValue());
            result.add(new PathCount(path, views));
        }
        return result;
    }

    /**
     * 把业务窗口字符串翻译成 GA4 DateRange。
     * "all" 用 2020-01-01 兜底（早于项目上线），避免真的不给 start 导致 API 报错。
     */
    private DateRange buildDateRange(String window) {
        return switch (window) {
            case "7d" -> DateRange.newBuilder().setStartDate("7daysAgo").setEndDate("today").build();
            case "all" -> DateRange.newBuilder().setStartDate("2020-01-01").setEndDate("today").build();
            default -> DateRange.newBuilder().setStartDate("30daysAgo").setEndDate("today").build();
        };
    }
}
