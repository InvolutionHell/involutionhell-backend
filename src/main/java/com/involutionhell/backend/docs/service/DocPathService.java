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
        if (path == null) return null;
        // 去 fragment
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        // strip locale 前缀 /zh/ 或 /en/
        path = path.replaceFirst("^/(zh|en)/", "/");
        // 去尾斜杠（根路径 "/" 不动）
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
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

        // docs.path_current 前缀是 content/，doc_paths.path 前缀是 app/
        // 后缀正则要先剥可选的 .en/.zh locale 段再剥 .md/.mdx：path_current 可能指向翻译版
        // 文件（如 leworldmodel.en.md），不剥 locale 段会让 canonical 漏出 ".en" 拼成死链。
        String sql = """
                SELECT canonical_path FROM (
                    SELECT regexp_replace(
                               regexp_replace(d.path_current, '^content', ''),
                               '(/index)?(\\.(en|zh))?\\.(mdx|md)$', ''
                           ) AS match_path,
                           regexp_replace(
                               regexp_replace(d.path_current, '^content', ''),
                               '(/index)?(\\.(en|zh))?\\.(mdx|md)$', ''
                           ) AS canonical_path
                    FROM docs d
                    WHERE d.path_current IS NOT NULL
                    UNION ALL
                    SELECT regexp_replace(
                               regexp_replace(dp.path, '^app', ''),
                               '(/index)?(\\.(en|zh))?\\.(mdx|md)$', ''
                           ) AS match_path,
                           regexp_replace(
                               regexp_replace(d.path_current, '^content', ''),
                               '(/index)?(\\.(en|zh))?\\.(mdx|md)$', ''
                           ) AS canonical_path
                    FROM doc_paths dp
                    JOIN docs d ON d.id = dp.doc_id
                    WHERE d.path_current IS NOT NULL
                ) t
                WHERE match_path = ?
                LIMIT 1
                """;

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
