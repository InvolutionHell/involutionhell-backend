package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.util.DomainWhitelist;
import org.junit.jupiter.api.BeforeEach;
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
 * 2. 非白名单 + 无 flag → APPROVED（简化后信任 AI 无 flag 即放行）
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
    private OgFallbackService ogFallbackService;

    @Mock
    private ClassificationService classificationService;

    @Mock
    private SharedLinkService sharedLinkService;

    @Mock
    private AlertWebhookClient alertWebhookClient;

    @InjectMocks
    private SharedLinkEnrichmentWorker worker;

    /**
     * 默认 stub：兜底服务返回空 Guess，避免 ogTitle 为空的 case 走进
     * {@code guess.isEmpty()} 时 Mockito 默认返回 null 触发 NPE
     * （现在虽然被 worker 内的 catch 吞掉，但显式 stub 比依赖 catch 更可读）。
     * 单测想验证兜底命中时可在该用例内 override。
     */
    @BeforeEach
    void stubFallbackDefaults() {
        // lenient：部分场景（如 link not found）根本不会进入兜底分支
        org.mockito.Mockito.lenient()
                .when(ogFallbackService.guess(any(), any()))
                .thenReturn(OgFallbackService.Guess.empty());
    }

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
    void enrich_nonFlagged_doesNotFireWebhook() {
        // APPROVED 路径不该触发告警 webhook，防未来改 enrichment 时误拓宽 alert
        String host = "example.com";
        SharedLink link = stubLink(100L, "https://example.com/x", host);
        when(sharedLinkService.findById(100L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("标题", null, null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("other", false, false, false, false, false));

        worker.enrich(100L);

        verify(alertWebhookClient, never()).notifyFlagged(any(SharedLink.class), anyMap());
    }

    @Test
    void enrich_whitelistDomain_noFlags_statusBecomesApproved() {
        String host = "mp.weixin.qq.com"; // 白名单域名
        assertThat(DomainWhitelist.contains(host)).isTrue(); // 确保测试前提成立

        SharedLink link = stubLink(1L, "https://mp.weixin.qq.com/s/abc", host);
        when(sharedLinkService.findById(1L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("标题", "描述", "https://cover.jpg", "某公众号", null));
        when(classificationService.classify(anyString(), anyString(), anyString())).thenReturn(
                new ClassificationResult("engineering", false, false, false, false, false));

        worker.enrich(1L);

        // 验证 enrich 被调用，且 finalStatus = APPROVED
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(1L),
                anyString(), anyString(), anyString(), anyString(), isNull(),
                anyString(), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.APPROVED);
    }

    // ── 场景 2：非白名单域名 + 无 flag → APPROVED（2026-04-24 简化后） ──────────────────

    @Test
    void enrich_nonWhitelistDomain_noFlags_statusBecomesApproved_afterSimplification() {
        String host = "example.com"; // 非白名单
        assertThat(DomainWhitelist.contains(host)).isFalse();

        SharedLink link = stubLink(2L, "https://example.com/article", host);
        when(sharedLinkService.findById(2L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("非白名单文章", null, null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("other", false, false, false, false, false));

        worker.enrich(2L);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(2L),
                any(), any(), any(), any(), any(),
                any(), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.APPROVED);
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
                new ClassificationResult("other", false, true, false, false, false)); // ad=true

        worker.enrich(3L);

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(3L),
                any(), any(), any(), any(), any(),
                any(), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.FLAGGED);
        // FLAGGED 必须触发 webhook（用于即时告警，防止未来重构时悄悄丢失）
        verify(alertWebhookClient, times(1)).notifyFlagged(any(SharedLink.class), anyMap());
    }

    @Test
    void enrich_nsfwFlag_statusBecomesFlagged() {
        SharedLink link = stubLink(4L, "https://zhuanlan.zhihu.com/p/999", "zhuanlan.zhihu.com");
        when(sharedLinkService.findById(4L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult("问题标题", null, null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("lifestyle", true, false, false, false, false)); // nsfw=true

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
                new ClassificationResult("other", false, false, false, false, false));

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

        // 分类降级：category=other, flags 全 false → APPROVED（AI 说无问题就放行）
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        verify(sharedLinkService).enrich(eq(6L),
                any(), any(), any(), any(), any(),
                eq("other"), anyMap(), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(SharedLinkStatus.APPROVED);
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
                new ClassificationResult("industry", false, false, true, false, false)); // flame=true

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

    // ── 场景 8：notResource=true → FLAGGED（兜底拦表情包/裸图片/dev URL） ────

    @Test
    void enrich_notResourceFlag_routesToFlagged() {
        String host = "klipy.com";
        SharedLink link = stubLink(8L, "https://klipy.com/gifs/hello-1234", host);
        when(sharedLinkService.findById(8L)).thenReturn(Optional.of(link));
        when(ogFetchService.fetch(anyString())).thenReturn(
                new OgFetchResult(null, null, null, null, null));
        when(classificationService.classify(any(), any(), any())).thenReturn(
                new ClassificationResult("other", false, false, false, false, true)); // notResource=true

        worker.enrich(8L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Boolean>> flagsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sharedLinkService).enrich(eq(8L),
                any(), any(), any(), any(), any(),
                any(), flagsCaptor.capture(), eq(SharedLinkStatus.FLAGGED));

        Map<String, Boolean> flags = flagsCaptor.getValue();
        assertThat(flags.get("nsfw")).isFalse();
        assertThat(flags.get("ad")).isFalse();
        assertThat(flags.get("flame")).isFalse();
        assertThat(flags.get("illegal")).isFalse();
        assertThat(flags.get("notResource")).isTrue();
    }
}
