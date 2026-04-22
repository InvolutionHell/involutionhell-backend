package com.involutionhell.backend.analytics.service;

import com.involutionhell.backend.analytics.dto.TopDocDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        // GA4 里一篇文章可能拆成 "?utm_source" / 带尾斜杠 / 有 anchor 等多条记录，
        // 所以拉一点余量再按归一化后的 path 合并，保证榜单里 views 是同一篇的累加值。
        int fetchSize = Math.min(Math.max(limit * 3, 30), 100);
        List<Ga4ReportService.PathCount> pathCounts = ga4ReportService.fetchTopPaths(window, fetchSize);

        if (pathCounts.isEmpty()) {
            return List.of();
        }

        Map<String, Long> mergedViews = new LinkedHashMap<>();
        for (Ga4ReportService.PathCount pc : pathCounts) {
            String normalized = normalizePath(pc.path());
            if (normalized.isEmpty()) continue;
            mergedViews.merge(normalized, pc.views(), Long::sum);
        }

        List<String> paths = new ArrayList<>(mergedViews.keySet());
        Map<String, String> pathToTitle = queryDocTitles(paths);

        return mergedViews.entrySet().stream()
                .filter(e -> pathToTitle.containsKey(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new TopDocDto(e.getKey(), pathToTitle.get(e.getKey()), e.getValue()))
                .limit(limit)
                .toList();
    }

    /**
     * 归一化 GA4 pagePath：只做 query / anchor / 尾斜杠清洗，不再做任何 IA 路径重写。
     * 历史 IA（比如 2026-04-19 重组前的 /docs/ai/* / /docs/CommunityShare/*）要靠 DB
     * 里的 doc_paths 行来命中，见 {@link #queryDocTitles}。
     * 对外暴露为 package-private 便于单元测试。
     */
    String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "";
        // GA4 可能把 ?utm_source=... / #section 拆成独立 pagePath，拆分后 views 分散到多条
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        // 去掉尾部斜杠：docs.path_current / doc_paths.path 正则归一化后都不带尾斜杠
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 查询 docs 表，把 GA4 返回的 pagePath 批量映射成标题。
     *
     * <p>这里做的事：把 docs.path_current（当前文件路径）和 doc_paths.path（历史文件路径）
     * 一起纳入候选，用同一套 PostgreSQL 正则去掉 {@code ^app} 前缀与 {@code (/index)?\.(mdx|md)$}
     * 后缀后与 GA4 的 pagePath 对齐。这样 2026-04-19 IA 重组之前的老 URL
     * （比如 /docs/ai/multimodal/qwenvl）能通过 doc_paths 命中到当前 docs 行，
     * 30D / ALL 窗口的历史流量不丢。
     *
     * <p>前提：{@code doc_paths} 里要有对应的老路径。前端 scripts/backfill-contributors.mjs
     * 每次跑都会 upsert"当前文件"路径（只增不减），加上
     * {@code backend/docs/migrations/2026-04-22-seed-ia-reorg-doc-paths.sql} 一次性回填的
     * IA 重组前前缀别名，两者一起覆盖了绝大部分历史流量。
     *
     * <p>GA4 pagePath 形如 {@code /docs/ai/multimodal/qwenvl}；
     * path_current / doc_paths.path 形如 {@code app/docs/ai/multimodal/qwenvl/index.mdx}
     * 或 {@code app/docs/.../xxx.mdx}。
     *
     * <p>查询失败直接抛 {@link IllegalStateException}，由全局异常处理器返回 500，
     * 不再返回空 Map 导致上层 containsKey 过滤把整个榜单静默清空。
     */
    private Map<String, String> queryDocTitles(List<String> paths) {
        if (paths.isEmpty()) return Map.of();

        try {
            // UNION ALL：同一个 doc 既能被 path_current 命中、也能被 doc_paths 里任一历史
            // 路径命中；多行会被下面 Collectors.toMap 的 merge 函数收敛成一条（保留任一 title）。
            String sql = """
                    SELECT normalized AS path_current, title
                    FROM (
                        SELECT d.title,
                               regexp_replace(
                                   regexp_replace(d.path_current, '^app', ''),
                                   '(/index)?\\.(mdx|md)$', ''
                               ) AS normalized
                        FROM docs d
                        WHERE d.path_current IS NOT NULL
                        UNION ALL
                        SELECT d.title,
                               regexp_replace(
                                   regexp_replace(dp.path, '^app', ''),
                                   '(/index)?\\.(mdx|md)$', ''
                               ) AS normalized
                        FROM doc_paths dp
                        JOIN docs d ON d.id = dp.doc_id
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
