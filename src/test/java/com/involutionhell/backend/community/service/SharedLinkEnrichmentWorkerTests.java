package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.util.DomainWhitelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SharedLinkEnrichmentWorker 单元测试。
 *
 * 用 Mockito 模拟三个协作服务，验证：
 * 1. 白名单 + 无 flag → APPROVED
 * 2. 非白名单 + 无 flag → PENDING_MANUAL
 * 3. 任一 flag 命中 → FLAGGED
 * 4. OG 抓取失败（降级）后仍能正常完成 enrichment
 * 5. DeepSeek 分类失败（降级）后仍能推进 status
 * 6. 链接不存在时跳过（不调 enrich）
 */
@ExtendWith(MockitoExtension.class)
class SharedLinkEnrichmentWorkerTests {

    @Mock
    private OgFetchService ogFetchService;

    @Mock
    private ClassificationService classificationService;

    @Mock
    private SharedLinkService sharedLinkService;

    @Mock
    private AlertWebhookClient alertWebhookClient;

    @InjectMocks
    private SharedLinkEnrichmentWorker worker;

    // ── 工具方法 ──────────────────────────────────────────────────────────

    /**
     * 构造一个最简 SharedLink stub，只需要 id / url / host 三个字段。
     */
    private SharedLink stubLink(Long id, String url, String host) {
        return new SharedLink(
                id, 1L, url, "hash", host,
                "推荐理由",
                null, null, null, null, null,
                null, Map.of(), SharedLinkStatus.PENDING,
                0, null, null,
                Instant.now(), Instant.now()
        );
    }

    // ── 场景 1：白名单域名 + 无 flag → APPROVED ───────────────────────────

    @Test
    void enrich_whitelistDomain_noFlags_statusBecomesApproved() {
        String host = "mp.weixin.qq.com"; // 白名单域名
        assertThat(DomainWhitelist.contains(host)).isTrue(); // 确保测试前提成立

        SharedLink link = stubLink(1L, "https://mp.weixin.qq.com/s/abc", host);
        when(sharedLinkService.findById(1L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("标题", "描述", "https://cover.jpg", "某公众号", null));
        when(classificationService.classify(anyString(), anyString(), anyString())).thenReturn(
                new ClassificationResult("engineering", false, false, false));

        worker.enrich(1L);

        // 验证 enrich 被调用，且 finalStatus = APPROVED
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(1L),
                anyString(), anyString(), anyString(), anyString(), isNull(),
                anyString(), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.APPROVED);
    }

    // ── 场景 2：非白名单域名 + 无 flag → PENDING_MANUAL ──────────────────

    @Test
    void enrich_nonWhitelistDomain_noFlags_statusBecomesPendingManual() {
        String host = "example.com"; // 非白名单
        assertThat(DomainWhitelist.contains(host)).isFalse();

        SharedLink link = stubLink(2L, "https://example.com/article", host);
        when(sharedLinkService.findById(2L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("非白名单文章", null, null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("other", false, false, false));

        worker.enrich(2L);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(2L),
                any(), any(), any(), any(), any(),
                any(), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.PENDING_MANUAL);
    }

    // ── 场景 3：任一 flag 命中 → FLAGGED（忽略白名单）────────────────────

    @Test
    void enrich_flaggedByAd_statusBecomesFlagged_regardlessOfWhitelist() {
        String host = "mp.weixin.qq.com"; // 白名单，但有 ad flag
        SharedLink link = stubLink(3L, "https://mp.weixin.qq.com/s/ad", host);
        when(sharedLinkService.findById(3L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("限时特卖！", "买一送一", null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("other", false, true, false)); // ad=true

        worker.enrich(3L);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(3L),
                any(), any(), any(), any(), any(),
                any(), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.FLAGGED);
    }

    @Test
    void enrich_nsfwFlag_statusBecomesFlagged() {
        SharedLink link = stubLink(4L, "https://zhuanlan.zhihu.com/p/999", "zhuanlan.zhihu.com");
        when(sharedLinkService.findById(4L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("问题标题", null, null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("lifestyle", true, false, false)); // nsfw=true

        worker.enrich(4L);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(4L),
                any(), any(), any(), any(), any(),
                any(), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.FLAGGED);
    }

    // ── 场景 4：OG 抓取失败（降级）→ 仍能完成 enrichment ────────────────

    @Test
    void enrich_ogFetchFails_stillCompletesEnrichment() {
        String host = "mp.weixin.qq.com";
        SharedLink link = stubLink(5L, "https://mp.weixin.qq.com/s/gone", host);
        when(sharedLinkService.findById(5L)).thenReturn(Optional.of(link));
        // OG 抓取失败，返回降级结果
        when(ogFetchService.fetch(anyString())).thenReturn(
                OgFetchResult.failure("HTTP 403"));
        when(classificationService.classify(isNull(), isNull(), eq(host))).thenReturn(
                new ClassificationResult("other", false, false, false));

        worker.enrich(5L);

        // enrich 必须被调用（status 从 PENDING 推进）
        verify(sharedLinkService).enrich(eq(5L),
                isNull(), isNull(), isNull(), isNull(),
                eq("HTTP 403"),  // ogFetchError 记录失败原因
                eq("other"), anyMap(), eq(SharedLinkStatus.APPROVED));
    }

    // ── 场景 5：DeepSeek 分类失败（降级）→ 仍能推进 status ───────────────

    @Test
    void enrich_classificationFails_stillPushesStatusForward() {
        String host = "example.com"; // 非白名单
        SharedLink link = stubLink(6L, "https://example.com/article", host);
        when(sharedLinkService.findById(6L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("好文", "内容", null, null, null));
        // 分类服务降级
        when(classificationService.classify(any(), any(), any())).thenReturn(
                ClassificationResult.fallback());

        worker.enrich(6L);

        // 分类降级：category=other, flags 全 false，非白名单 → PENDING_MANUAL
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(6L),
                any(), any(), any(), any(), any(),
                eq("other"), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.PENDING_MANUAL);
    }

    // ── 场景 6：链接不存在 → 跳过，不调 enrich ───────────────────────────

    @Test
    void enrich_linkNotFound_doesNotCallEnrich() {
        when(sharedLinkService.findById(999L)).thenReturn(Optional.empty());

        worker.enrich(999L);

        // 链接不存在，不调用 OG 抓取、分类、也不回填
        verify(ogFetchService, never()).fetch(any());
        verify(classificationService, never()).classify(any(), any(), any());
        verify(sharedLinkService, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── 场景 7：flags map 内容正确 ────────────────────────────────────────

    @Test
    void enrich_flameFlag_flagsMapContainsCorrectValues() {
        String host = "example.com";
        SharedLink link = stubLink(7L, "https://example.com/flame", host);
        when(sharedLinkService.findById(7L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("引战标题", null, null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("industry", false, false, true)); // flame=true

        worker.enrich(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Boolean>> flagsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sharedLinkService).enrich(eq(7L),
                any(), any(), any(), any(), any(),
                any(), flagsCaptor.capture(), eq(SharedLinkStatus.FLAGGED));

        Map<String, Boolean> flags = flagsCaptor.getValue();
        assertThat(flags.get("nsfw")).isFalse();
        assertThat(flags.get("ad")).isFalse();
        assertThat(flags.get("flame")).isTrue();
    }
}
