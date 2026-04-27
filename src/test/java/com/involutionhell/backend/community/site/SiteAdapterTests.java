package com.involutionhell.backend.community.site;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SiteAdapter 链 + 各 adapter 的单元测试。
 *
 * 不跑网络，纯 URL 字符串变换断言。
 */
class SiteAdapterTests {

    private final ArxivPdfAdapter arxiv = new ArxivPdfAdapter();
    private final ScholarUrlAdapter scholar = new ScholarUrlAdapter();
    private final UrlNormalizer normalizer = new UrlNormalizer(List.of(scholar, arxiv));

    // ── ArxivPdfAdapter ──────────────────────────────────────────────────

    @Test
    void arxiv_pdfWithoutSuffix_normalizedToAbs() {
        assertThat(arxiv.normalize("https://arxiv.org/pdf/2603.15381"))
                .isEqualTo("https://arxiv.org/abs/2603.15381");
    }

    @Test
    void arxiv_pdfWithSuffix_normalizedToAbs() {
        assertThat(arxiv.normalize("https://arxiv.org/pdf/2603.15381.pdf"))
                .isEqualTo("https://arxiv.org/abs/2603.15381");
    }

    @Test
    void arxiv_pdfWithVersion_keepsVersion() {
        assertThat(arxiv.normalize("https://arxiv.org/pdf/2603.15381v2"))
                .isEqualTo("https://arxiv.org/abs/2603.15381v2");
    }

    @Test
    void arxiv_absUrl_unchanged() {
        assertThat(arxiv.normalize("https://arxiv.org/abs/2603.15381"))
                .isEqualTo("https://arxiv.org/abs/2603.15381");
    }

    @Test
    void arxiv_otherHost_unchanged() {
        assertThat(arxiv.normalize("https://example.com/pdf/2603.15381"))
                .isEqualTo("https://example.com/pdf/2603.15381");
    }

    @Test
    void arxiv_exportSubdomain_handled() {
        assertThat(arxiv.normalize("https://export.arxiv.org/pdf/2603.15381"))
                .isEqualTo("https://export.arxiv.org/abs/2603.15381");
    }

    // ── ScholarUrlAdapter ────────────────────────────────────────────────

    @Test
    void scholar_extractsRealUrlFromQuery() {
        String input = "https://scholar.google.com/scholar_url?url=https://arxiv.org/pdf/2604.15699&hl=zh-CN";
        assertThat(scholar.normalize(input))
                .isEqualTo("https://arxiv.org/pdf/2604.15699");
    }

    @Test
    void scholar_handlesUrlEncodedTarget() {
        // url= 参数内部的 ? 和 & 必须 URL-encode 才能正确解析
        String input = "https://scholar.google.com/scholar_url?url=https%3A%2F%2Fexample.com%2Fpaper%3Fid%3D42";
        assertThat(scholar.normalize(input))
                .isEqualTo("https://example.com/paper?id=42");
    }

    @Test
    void scholar_googleHongKongAlsoWorks() {
        String input = "https://scholar.google.com.hk/scholar_url?url=https://example.com/p";
        assertThat(scholar.normalize(input))
                .isEqualTo("https://example.com/p");
    }

    @Test
    void scholar_nonScholarPath_unchanged() {
        assertThat(scholar.normalize("https://scholar.google.com/citations?user=abc"))
                .startsWith("https://scholar.google.com/citations");
    }

    @Test
    void scholar_missingUrlParam_unchanged() {
        String input = "https://scholar.google.com/scholar_url?hl=zh-CN";
        assertThat(scholar.normalize(input)).isEqualTo(input);
    }

    @Test
    void scholar_nonHttpUrlValue_unchanged() {
        // 防御：url= 解码出来不是 http(s) 不放行（防止 javascript: / file: 等）
        String input = "https://scholar.google.com/scholar_url?url=javascript:alert(1)";
        assertThat(scholar.normalize(input)).isEqualTo(input);
    }

    // ── UrlNormalizer 链式 ───────────────────────────────────────────────

    @Test
    void chain_scholarThenArxiv_endsAtArxivAbs() {
        // scholar URL 提出 arxiv pdf URL，再被 arxiv adapter 转 abs
        String input = "https://scholar.google.com/scholar_url?url=https://arxiv.org/pdf/2604.15699";
        assertThat(normalizer.normalize(input))
                .isEqualTo("https://arxiv.org/abs/2604.15699");
    }

    @Test
    void chain_normalUrl_unchanged() {
        String input = "https://github.com/InvolutionHell/involutionhell";
        assertThat(normalizer.normalize(input)).isEqualTo(input);
    }

    @Test
    void chain_handlesNullSafely() {
        assertThat(normalizer.normalize(null)).isNull();
    }

    @Test
    void chain_emptyAdapterList_returnsOriginal() {
        UrlNormalizer empty = new UrlNormalizer(List.of());
        assertThat(empty.normalize("https://example.com")).isEqualTo("https://example.com");
    }
}
