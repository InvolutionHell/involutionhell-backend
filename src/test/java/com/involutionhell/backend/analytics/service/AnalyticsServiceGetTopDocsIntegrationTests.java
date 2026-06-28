package com.involutionhell.backend.analytics.service;

import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import com.involutionhell.backend.analytics.dto.TopDocDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 真跑 JdbcTemplate 的 AnalyticsService 集成测试：H2 PostgreSQL 模式下演练
 * queryDocInfo 里的 UNION SQL，确保
 *   1) 当前 docs.path_current 命中（前缀 content/）
 *   2) 老路径通过 doc_paths.path 命中（前缀 app/，模拟 IA 重组前的 GA4 pagePath）
 *   3) query / anchor / 尾斜杠 / locale 前缀清洗后仍能命中
 *   4) 没录入过的路径被过滤掉，不会因 null 炸掉榜单
 *   5) canonical 永远不泄漏 content/ 前缀，也不漏 .en/.zh locale 后缀
 *
 * 注意 path_current 的前缀必须是 content/（生产真实格式）。历史上本测试 seed 成 app/，
 * 正好对上当时 queryDocInfo 误用的 ^app，测试绿但生产榜单链接全部泄漏 content/ 前缀 404。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analytics;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        "justauth.type.github.redirect-uri=https://example.com/api/auth/callback/github",
        "justauth.type.github.client-id=test-client-id",
        "justauth.type.github.client-secret=test-client-secret"
})
@ActiveProfiles("test")
class AnalyticsServiceGetTopDocsIntegrationTests {

    // GA4 真实 gRPC 客户端不能在测试环境活起来，必须 mock 掉
    @MockitoBean
    private BetaAnalyticsDataClient betaAnalyticsDataClient;

    // 伪造 GA4 返回的 pagePath 列表，真正被测的是 AnalyticsService 对 DB 的查询
    @MockitoBean
    private Ga4ReportService ga4ReportService;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetState() {
        // 清 docs / doc_paths，每个用例按需自己灌数据，避免互相污染
        jdbcTemplate.update("DELETE FROM doc_paths");
        jdbcTemplate.update("DELETE FROM docs");
        // getTopDocs 带 @Cacheable，不清的话第二个用例拿的是第一个的结果
        Cache cache = cacheManager.getCache("topDocs");
        if (cache != null) cache.clear();
    }

    /** path_current 必须是生产真实前缀 content/。 */
    private void insertDoc(String id, String pathCurrent, String title) {
        jdbcTemplate.update(
                "INSERT INTO docs (id, path_current, title) VALUES (?, ?, ?)",
                id, pathCurrent, title
        );
    }

    /** doc_paths.path 是历史前缀 app/。 */
    private void insertDocPath(String docId, String path) {
        jdbcTemplate.update(
                "INSERT INTO doc_paths (doc_id, path) VALUES (?, ?)",
                docId, path
        );
    }

    @Test
    void matchesCurrentPathAfterRegexpNormalization() {
        insertDoc("qwenvl", "content/docs/learn/ai/multimodal/qwenvl/index.mdx", "QwenVL 多模态");

        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/docs/learn/ai/multimodal/qwenvl", 500)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("7d", 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai/multimodal/qwenvl");
        assertThat(result.get(0).title()).isEqualTo("QwenVL 多模态");
        assertThat(result.get(0).views()).isEqualTo(500L);
    }

    @Test
    void canonicalDoesNotLeakContentPrefix() {
        // 回归：path_current 是 content/ 前缀，canonical 决不能带 content/，否则前端拼出 404
        insertDoc("ai", "content/docs/learn/ai/index.mdx", "AI 知识库");

        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/docs/learn/ai", 3000)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("all", 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai");
        assertThat(result.get(0).path()).doesNotStartWith("content/");
    }

    @Test
    void localePrefixedGa4PathMatchesAndMergesAcrossLocales() {
        // 段化后 GA4 pagePath 带 locale 前缀；normalizePath 剥掉后 zh/en 同篇合并
        insertDoc("ai", "content/docs/learn/ai/index.mdx", "AI 知识库");

        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/en/docs/learn/ai", 2000),
                new Ga4ReportService.PathCount("/zh/docs/learn/ai", 1000)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("30d", 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai");
        assertThat(result.get(0).views()).isEqualTo(3000L);
    }

    @Test
    void translatedDocCanonicalHasNoLocaleSuffix() {
        // path_current 指向翻译版文件，canonical 不能漏出 .en
        insertDoc("lwm", "content/docs/learn/ai/papers/leworldmodel.en.md", "LeWorldModel");

        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/en/docs/learn/ai/papers/leworldmodel", 400)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("all", 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai/papers/leworldmodel");
    }

    @Test
    void historicalPathResolvesToCurrentCanonicalUrl() {
        // docs 表里存的是 IA 重组后的新路径（content/ 前缀）
        insertDoc("qwenvl", "content/docs/learn/ai/multimodal/qwenvl/index.mdx", "QwenVL 多模态");
        // doc_paths 里留有 IA 重组前的老路径（app/ 前缀，seed SQL 或 backfill 写入）
        insertDocPath("qwenvl", "app/docs/ai/multimodal/qwenvl/index.mdx");

        // GA4 给的是老 URL（ALL 窗口里的历史流量）
        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/docs/ai/multimodal/qwenvl", 1200)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("all", 20);

        assertThat(result).hasSize(1);
        // 榜单对外暴露的是当前 canonical URL，避免前端点进去再走一次 301
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai/multimodal/qwenvl");
        assertThat(result.get(0).title()).isEqualTo("QwenVL 多模态");
        assertThat(result.get(0).views()).isEqualTo(1200L);
    }

    @Test
    void mergesOldAndNewUrlIntoSingleCanonicalEntry() {
        insertDoc("qwenvl", "content/docs/learn/ai/multimodal/qwenvl/index.mdx", "QwenVL 多模态");
        insertDocPath("qwenvl", "app/docs/ai/multimodal/qwenvl/index.mdx");

        // IA 重组的那段时间 GA4 同时积累了老 URL 和新 URL 的访问
        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/docs/ai/multimodal/qwenvl", 800),
                new Ga4ReportService.PathCount("/docs/learn/ai/multimodal/qwenvl", 200)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("all", 20);

        // 榜单里应只出现一条 canonical URL，views 合并
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai/multimodal/qwenvl");
        assertThat(result.get(0).views()).isEqualTo(1000L);
    }

    @Test
    void stripsQueryAndAnchorBeforeMatching() {
        insertDoc("intro", "content/docs/learn/ai/intro.mdx", "AI 入门");

        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/docs/learn/ai/intro?utm_source=x", 100),
                new Ga4ReportService.PathCount("/docs/learn/ai/intro#what-is-ai", 200),
                new Ga4ReportService.PathCount("/docs/learn/ai/intro/", 300)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("30d", 20);

        // 三条 GA4 记录应合并成同一篇，views = 600
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai/intro");
        assertThat(result.get(0).views()).isEqualTo(600L);
    }

    @Test
    void filtersOutPathsWithNoMatchingDoc() {
        insertDoc("intro", "content/docs/learn/ai/intro.mdx", "AI 入门");

        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of(
                new Ga4ReportService.PathCount("/docs/learn/ai/intro", 500),
                new Ga4ReportService.PathCount("/docs/not-a-real-page", 99999)
        ));

        List<TopDocDto> result = analyticsService.getTopDocs("7d", 20);

        // 不存在的路径被过滤掉，避免给 UI 推一条 title=null 的废数据
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("/docs/learn/ai/intro");
    }

    @Test
    void returnsEmptyWhenGa4ReturnsNothing() {
        insertDoc("intro", "content/docs/learn/ai/intro.mdx", "AI 入门");
        when(ga4ReportService.fetchTopPaths(anyString(), anyInt())).thenReturn(List.of());

        List<TopDocDto> result = analyticsService.getTopDocs("7d", 20);

        assertThat(result).isEmpty();
    }
}
