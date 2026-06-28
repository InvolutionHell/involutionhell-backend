package com.involutionhell.backend.analytics.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单测 AnalyticsService.normalizePath：只验证 GA4 pagePath 的 query / anchor / 尾斜杠清洗，
 * IA 重组的路径别名已改成走 DB 里的 doc_paths 表（见 queryDocTitles），不再在代码层做前缀替换。
 * 不需要 SpringBootContext，直接 new 实例调包内方法即可（Ga4ReportService / JdbcTemplate
 * 在 normalizePath 里不参与，传 null 足够）。
 */
class AnalyticsServiceNormalizePathTests {

    private final AnalyticsService service = new AnalyticsService(null, null);

    @Test
    void stripsQueryString() {
        assertThat(service.normalizePath("/docs/learn/ai/intro?utm_source=twitter"))
                .isEqualTo("/docs/learn/ai/intro");
    }

    @Test
    void stripsAnchor() {
        assertThat(service.normalizePath("/docs/learn/ai/intro#section"))
                .isEqualTo("/docs/learn/ai/intro");
    }

    @Test
    void stripsTrailingSlash() {
        assertThat(service.normalizePath("/docs/learn/ai/intro/"))
                .isEqualTo("/docs/learn/ai/intro");
    }

    @Test
    void preservesRootSlash() {
        assertThat(service.normalizePath("/")).isEqualTo("/");
    }

    @Test
    void stripsCombinedQueryAnchorAndSlash() {
        assertThat(service.normalizePath("/docs/learn/ai/intro/?ref=rank#top"))
                .isEqualTo("/docs/learn/ai/intro");
    }

    @Test
    void preservesHistoricalIaPathUntouched() {
        // IA 重组前的老路径保持原样返回；是否能对上当前文章由 doc_paths 表决定，
        // 不再在归一化阶段做 /docs/ai/ → /docs/learn/ai/ 之类的前缀替换。
        assertThat(service.normalizePath("/docs/ai/multimodal/qwenvl"))
                .isEqualTo("/docs/ai/multimodal/qwenvl");
    }

    @Test
    void preservesCurrentIaPathUntouched() {
        assertThat(service.normalizePath("/docs/learn/ai/foundation-models/llm"))
                .isEqualTo("/docs/learn/ai/foundation-models/llm");
    }

    @Test
    void nullInputReturnsEmpty() {
        assertThat(service.normalizePath(null)).isEmpty();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(service.normalizePath("")).isEmpty();
    }

    @Test
    void stripsEnLocalePrefix() {
        // 段化后 GA4 pagePath 带 locale 前缀，要剥掉才能对上无 locale 的 match_path
        assertThat(service.normalizePath("/en/docs/learn/ai")).isEqualTo("/docs/learn/ai");
    }

    @Test
    void stripsZhLocalePrefix() {
        assertThat(service.normalizePath("/zh/docs/learn/ai/intro")).isEqualTo("/docs/learn/ai/intro");
    }

    @Test
    void stripsLocalePrefixWithQueryAndTrailingSlash() {
        assertThat(service.normalizePath("/en/docs/learn/ai/?ref=rank")).isEqualTo("/docs/learn/ai");
    }

    @Test
    void doesNotStripNonLocaleFirstSegment() {
        // /docs/... 本身不带 locale 段，不能被误伤
        assertThat(service.normalizePath("/docs/learn/ai")).isEqualTo("/docs/learn/ai");
    }
}
