package com.involutionhell.backend.docs.service;

/**
 * docs / doc_paths 文件路径 → canonical URL 的归一化 SQL 片段，单一来源。
 *
 * <p>{@link DocPathService}（/api/docs/resolve）和
 * {@link com.involutionhell.backend.analytics.service.AnalyticsService}（top-docs 榜单）
 * 都要把文件路径列归一化成站点 URL，规则必须一致。历史上这段正则被各抄一份，
 * AnalyticsService 漏改 {@code ^app→^content} + 漏剥 locale 后缀，导致 top-docs
 * 榜单链接泄漏 {@code content/} 前缀（点进去 404）。统一到这里避免再次漂移。
 *
 * <p>归一化规则：剥列的仓库前缀（{@code content} / {@code app} 字面量，非用户输入，
 * 无注入风险）、可选的 {@code .en}/{@code .zh} locale 段、可选的 {@code /index}、
 * {@code .md}/{@code .mdx} 后缀，得到无 locale、无后缀、无尾斜杠的 {@code /docs/...} 形式。
 */
public final class DocPathSql {

    private DocPathSql() {}

    /**
     * @param column 文件路径列（如 {@code d.path_current} / {@code dp.path}）
     * @param prefix 该列的仓库前缀字面量：{@code content}（path_current）或 {@code app}（doc_paths.path）
     * @return 把该列归一化成 canonical URL 的 SQL 表达式
     */
    public static String canonicalExpr(String column, String prefix) {
        return "regexp_replace(regexp_replace(" + column + ", '^" + prefix
                + "', ''), '(/index)?(\\.(en|zh))?\\.(mdx|md)$', '')";
    }
}
