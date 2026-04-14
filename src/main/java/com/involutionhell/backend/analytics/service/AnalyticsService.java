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
        // 多取一些给过滤留余量：首页、/docs 以外的页面、父目录导航页（docs 表没对应记录）都会被剔掉
        int fetchSize = Math.min(Math.max(limit * 3, 30), 100);
        List<Ga4ReportService.PathCount> pathCounts = ga4ReportService.fetchTopPaths(window, fetchSize);

        if (pathCounts.isEmpty()) {
            return List.of();
        }

        List<String> paths = pathCounts.stream().map(Ga4ReportService.PathCount::path).toList();

        // 批量查 docs 表把 path 映射成标题；没匹配到的视为非文档页，直接剔除
        Map<String, String> pathToTitle = queryDocTitles(paths);

        return pathCounts.stream()
                .filter(pc -> pathToTitle.containsKey(pc.path()))
                .map(pc -> new TopDocDto(pc.path(), pathToTitle.get(pc.path()), pc.views()))
                .limit(limit)
                .toList();
    }

    /**
     * 查询 docs 表，把 GA4 返回的 pagePath 批量映射成标题。
     *
     * GA4 pagePath 形如 /docs/ai/multimodal/qwenvl
     * docs.path_current 形如 app/docs/ai/multimodal/qwenvl/index.mdx 或 app/docs/.../xxx.mdx
     * 用 PostgreSQL 正则归一化 path_current 为 URL 形式后再匹配。
     *
     * 查询失败直接抛 {@link IllegalStateException}，由全局异常处理器返回 500，
     * 不再返回空 Map 导致上层 containsKey 过滤把整个榜单静默清空。
     */
    private Map<String, String> queryDocTitles(List<String> paths) {
        if (paths.isEmpty()) return Map.of();

        try {
            String sql = """
                    SELECT normalized AS path_current, title
                    FROM (
                        SELECT title,
                               regexp_replace(
                                   regexp_replace(path_current, '^app', ''),
                                   '(/index)?\\.(mdx|md)$', ''
                               ) AS normalized
                        FROM docs
                    ) t
                    WHERE normalized = ANY(?)
                    """;
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
            log.error("查询 docs 表失败，无法构建标题映射", e);
            throw new IllegalStateException("查询 docs 表失败", e);
        }
    }
}
