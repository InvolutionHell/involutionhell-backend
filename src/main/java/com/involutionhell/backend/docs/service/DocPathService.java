package com.involutionhell.backend.docs.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 文档路径解析服务：将任意输入路径（含旧路径、带 locale 前缀、历史重命名路径）
 * 解析为 canonical URL（无 locale 前缀的 /docs/... 形式）。
 *
 * 数据来源：
 * - docs.path_current：当前路径（前缀 content/）
 * - doc_paths.path：历史路径（前缀 app/），通过 doc_id 关联 docs
 *
 * canonical 格式：/docs/... （无 locale，无后缀，无尾斜杠），
 * 由前端 Block 3 负责拼 locale 后再做最终跳转。
 */
@Service
public class DocPathService {

    private final JdbcTemplate jdbcTemplate;

    public DocPathService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 归一化输入路径：
     * - 去掉 URL fragment（# 后面）
     * - strip locale 前缀（/zh/ 或 /en/）
     * - 去尾斜杠
     */
    String normalize(String path) {
        // 复用 normalizeStatic 这一份实现，避免归一化逻辑两处漂移（@Cacheable key 和实际查询
        // 必须用同一套归一化，否则缓存键和查询输入对不上）。null 仍返回 null 供调用方短路。
        if (path == null) return null;
        return normalizeStatic(path);
    }

    /**
     * 解析路径对应的 canonical URL。
     *
     * 查询逻辑（UNION）：
     * 1. docs.path_current（前缀 content/）→ 当前路径同时作为 match_path 和 canonical_path
     * 2. doc_paths.path（前缀 app/）→ 历史路径作为 match_path，关联文档的 path_current 作为 canonical_path
     *
     * @Cacheable key 必须是 normalize() 之后的路径，
     * 保证 /zh/docs/... 和 /docs/... 命中同一条缓存。
     *
     * @param inputPath 原始输入路径（可能含 locale 前缀或历史路径）
     * @return canonical URL（如 /docs/community/dev-tips/git101），或 empty
     */
    @Cacheable(value = "doc-resolve", key = "T(com.involutionhell.backend.docs.service.DocPathService).normalizeStatic(#inputPath)")
    public Optional<String> resolveCanonical(String inputPath) {
        String normalizedPath = normalize(inputPath);
        if (normalizedPath == null || normalizedPath.isBlank()) {
            return Optional.empty();
        }

        // path_current 前缀 content/、doc_paths.path 前缀 app/；归一化正则统一走 DocPathSql，
        // 与 AnalyticsService 共用同一份，避免两处漂移。
        String docExpr = DocPathSql.canonicalExpr("d.path_current", "content");
        String histExpr = DocPathSql.canonicalExpr("dp.path", "app");
        String sql = "SELECT canonical_path FROM ("
                + "  SELECT " + docExpr + " AS match_path, " + docExpr + " AS canonical_path"
                + "  FROM docs d WHERE d.path_current IS NOT NULL"
                + "  UNION ALL"
                + "  SELECT " + histExpr + " AS match_path, " + docExpr + " AS canonical_path"
                + "  FROM doc_paths dp JOIN docs d ON d.id = dp.doc_id WHERE d.path_current IS NOT NULL"
                + ") t WHERE match_path = ? LIMIT 1";

        List<String> results = jdbcTemplate.queryForList(sql, String.class, normalizedPath);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * 供 @Cacheable SpEL key 表达式调用的静态版本 normalize。
     * Spring Cache 的 T(...) 语法要求方法为 public static。
     */
    public static String normalizeStatic(String path) {
        if (path == null) return "";
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        path = path.replaceFirst("^/(zh|en)/", "/");
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
