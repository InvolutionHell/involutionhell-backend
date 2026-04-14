package com.involutionhell.backend.analytics.service;

import com.involutionhell.backend.analytics.dto.TopDocDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final Ga4ReportService ga4ReportService;
    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(Ga4ReportService ga4ReportService, JdbcTemplate jdbcTemplate) {
        this.ga4ReportService = ga4ReportService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Cacheable(value = "topDocs", key = "#window + '_' + #limit")
    public List<TopDocDto> getTopDocs(String window, int limit) {
        List<Ga4ReportService.PathCount> pathCounts = ga4ReportService.fetchTopPaths(window, limit);

        if (pathCounts.isEmpty()) {
            return List.of();
        }

        List<String> paths = pathCounts.stream().map(Ga4ReportService.PathCount::path).toList();

        // 批量查 docs 表把 path 映射成标题
        Map<String, String> pathToTitle = queryDocTitles(paths);

        return pathCounts.stream()
                .map(pc -> new TopDocDto(pc.path(), pathToTitle.get(pc.path()), pc.views()))
                .toList();
    }

    private Map<String, String> queryDocTitles(List<String> paths) {
        if (paths.isEmpty()) return Map.of();

        try {
            String sql = "SELECT path_current, title FROM docs WHERE path_current = ANY(?)";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql,
                    (Object) paths.toArray(new String[0])
            );
            return rows.stream().collect(Collectors.toMap(
                    r -> (String) r.get("path_current"),
                    r -> (String) r.get("title"),
                    (a, b) -> a
            ));
        } catch (Exception e) {
            log.warn("查询 docs 表失败，将跳过标题映射: {}", e.getMessage());
            return Map.of();
        }
    }
}
