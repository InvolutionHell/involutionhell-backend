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

@Service
public class Ga4ReportService {

    private static final Logger log = LoggerFactory.getLogger(Ga4ReportService.class);

    public record PathCount(String path, long views) {}

    private final BetaAnalyticsDataClient client;
    private final String propertyId;

    public Ga4ReportService(BetaAnalyticsDataClient client, Ga4Properties ga4Properties) {
        this.client = client;
        this.propertyId = "properties/" + ga4Properties.getPropertyId();
    }

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

    private DateRange buildDateRange(String window) {
        return switch (window) {
            case "7d" -> DateRange.newBuilder().setStartDate("7daysAgo").setEndDate("today").build();
            case "all" -> DateRange.newBuilder().setStartDate("2020-01-01").setEndDate("today").build();
            default -> DateRange.newBuilder().setStartDate("30daysAgo").setEndDate("today").build();
        };
    }
}
