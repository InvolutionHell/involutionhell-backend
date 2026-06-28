package com.involutionhell.backend.analytics.service;

import com.involutionhell.backend.analytics.dto.TopDocDto;
import com.involutionhell.backend.docs.service.DocPathSql;
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

    /** 一条 match_path 解析出来的当前标题 + 规范 URL，用 record 省一个内部类。 */
    private record DocInfo(String canonicalPath, String title) {}

    @Cacheable(value = "topDocs", key = "#window + '_' + #limit")
    public List<TopDocDto> getTopDocs(String window, int limit) {
        // GA4 里一篇文章可能拆成 "?utm_source" / 带尾斜杠 / 有 anchor 等多条记录，
        // 所以拉一点余量再按归一化后的 path 合并，保证榜单里 views 是同一篇的累加值。
        int fetchSize = Math.min(Math.max(limit * 3, 30), 100);
        List<Ga4ReportService.PathCount> pathCounts = ga4ReportService.fetchTopPaths(window, fetchSize);

        if (pathCounts.isEmpty()) {
            return List.of();
        }

        // GA4 原始 pagePath 按归一化后的 match key 累加 views
        Map<String, Long> viewsByMatchPath = new LinkedHashMap<>();
        for (Ga4ReportService.PathCount pc : pathCounts) {
            String normalized = normalizePath(pc.path());
            if (normalized.isEmpty()) continue;
            viewsByMatchPath.merge(normalized, pc.views(), Long::sum);
        }

        List<String> paths = new ArrayList<>(viewsByMatchPath.keySet());
        Map<String, DocInfo> matchToDoc = queryDocInfo(paths);

        // 把 GA4 里的老 URL / 新 URL 全部归并到当前 canonical 路径上，
        // 这样榜单点击直接 200，不需要再走一次 301 redirect，
        // 搜索引擎爬榜单页时也能把权重直接传给当前规范 URL。
        Map<String, Long> viewsByCanonical = new LinkedHashMap<>();
        Map<String, String> titleByCanonical = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : viewsByMatchPath.entrySet()) {
            DocInfo info = matchToDoc.get(e.getKey());
            if (info == null) continue;
            viewsByCanonical.merge(info.canonicalPath(), e.getValue(), Long::sum);
            titleByCanonical.putIfAbsent(info.canonicalPath(), info.title());
        }

        return viewsByCanonical.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new TopDocDto(e.getKey(), titleByCanonical.get(e.getKey()), e.getValue()))
                .limit(limit)
                .toList();
    }

    /**
     * 归一化 GA4 pagePath：只做 query / anchor / 尾斜杠清洗，不再做任何 IA 路径重写。
     * 历史 IA（比如 2026-04-19 重组前的 /docs/ai/* / /docs/CommunityShare/*）要靠 DB
     * 里的 doc_paths 行来命中，见 {@link #queryDocInfo}。
     * 对外暴露为 package-private 便于单元测试。
     */
    String normalizePath(String path) {
        if (path == null || path.isEmpty()) return "";
        // GA4 可能把 ?utm_source=... / #section 拆成独立 pagePath，拆分后 views 分散到多条
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        // i18n 段化（2026-05）后 GA4 pagePath 带 locale 前缀（/en/docs/...、/zh/docs/...），
        // 但 match_path 是无 locale 的 /docs/...。不剥 locale 段，段化后的全部流量都对不上，
        // 榜单只剩段化前的老 URL（近 7d/30d 窗口几乎空）。剥掉后 zh/en 同篇 views 还会合并。
        path = path.replaceFirst("^/(zh|en)/", "/");
        // 去掉尾部斜杠：docs.path_current / doc_paths.path 正则归一化后都不带尾斜杠
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 查询 docs / doc_paths，把 GA4 归一化后的 pagePath 映射到 <当前规范 URL, 标题>。
     *
     * <p>这里做的事：把 docs.path_current（当前文件路径）和 doc_paths.path（历史文件路径）
     * 一起纳入候选，用同一套 PostgreSQL 正则去掉 {@code ^app} 前缀与 {@code (/index)?\.(mdx|md)$}
     * 后缀后与 GA4 的 pagePath 对齐。每行无论从哪侧命中，都带上这一 doc 的 current 规范 URL
     * （总是基于 docs.path_current 生成），上层用 canonical 做展示和排序 key，GA4 里的老
     * 路径和新路径就能合并成同一条榜单记录。
     *
     * <p>前提：{@code doc_paths} 里要有对应的老路径。前端 scripts/backfill-contributors.mjs
     * 每次跑都会 upsert"当前文件"路径（只增不减），加上
     * {@code backend/docs/migrations/2026-04-22-seed-ia-reorg-doc-paths.sql} 一次性回填的
     * IA 重组前前缀别名，两者一起覆盖了绝大部分历史流量。
     *
     * <p>GA4 pagePath 形如 {@code /en/docs/learn/ai}（段化后带 locale 前缀，normalizePath 剥掉）；
     * path_current 形如 {@code content/docs/learn/ai/index.mdx}，doc_paths.path 形如
     * {@code app/docs/ai/multimodal/qwenvl.mdx}（历史前缀 app/）。
     *
     * <p>查询失败直接抛 {@link IllegalStateException}，由全局异常处理器返回 500，
     * 不再返回空 Map 导致上层 containsKey 过滤把整个榜单静默清空。
     */
    private Map<String, DocInfo> queryDocInfo(List<String> paths) {
        if (paths.isEmpty()) return Map.of();

        try {
            // UNION ALL：一行 = 一个 (match_path, canonical_path, title) 候选。
            // - 从 docs 过来的：match 和 canonical 都是 path_current 归一化，指自己
            // - 从 doc_paths 过来的：match 是历史路径归一化，canonical 仍然是 path_current
            //   归一化（通过 JOIN docs 拿），所以老 URL 最终落到当前 URL 上
            // 归一化正则与 DocPathService 共用 DocPathSql：path_current 前缀 content/、
            // doc_paths.path 前缀 app/。曾误用 ^app 剥 path_current（content/ 剥不掉）→ canonical
            // 泄漏 content/ 前缀，榜单链接 404。
            String docExpr = DocPathSql.canonicalExpr("d.path_current", "content");
            String histExpr = DocPathSql.canonicalExpr("dp.path", "app");
            String sql = "SELECT match_path, canonical_path, title FROM ("
                    + "  SELECT " + docExpr + " AS match_path, " + docExpr + " AS canonical_path, d.title"
                    + "  FROM docs d WHERE d.path_current IS NOT NULL"
                    + "  UNION ALL"
                    + "  SELECT " + histExpr + " AS match_path, " + docExpr + " AS canonical_path, d.title"
                    + "  FROM doc_paths dp JOIN docs d ON d.id = dp.doc_id WHERE d.path_current IS NOT NULL"
                    + ") t WHERE match_path = ANY(?)";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql,
                    (Object) paths.toArray(new String[0])
            );
            return rows.stream().collect(Collectors.toMap(
                    r -> (String) r.get("match_path"),
                    r -> new DocInfo((String) r.get("canonical_path"), (String) r.get("title")),
                    // 同一个 match_path 理论上只会出现一次（docs 当前路径 + doc_paths 历史
                    // 路径不会撞）；万一撞了（脏数据），保留先到的那条
                    (a, b) -> a
            ));
        } catch (Exception e) {
            log.error("查询 docs 表失败，无法构建标题映射", e);
            throw new IllegalStateException("查询 docs 表失败", e);
        }
    }
}
