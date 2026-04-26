package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 社区分享链接异步富化 Worker（M4）。
 *
 * 在 submit() 成功后由 SharedLinkService 触发，运行在独立线程池中，
 * 不阻塞 HTTP 响应。
 *
 * 流程：
 * 1. OG 抓取（OgFetchService）→ 获取 title/description/cover/siteName
 * 2. DeepSeek 分类（ClassificationService）→ 获取 category + nsfw/ad/flame flags
 * 3. 决定最终 status：
 *    - 任一 flag 命中 → FLAGGED（进人工待审）
 *    - 白名单域名 + 无 flag → APPROVED（直接上公共流）
 *    - 非白名单 + 无 flag → PENDING_MANUAL（进人工待审）
 * 4. 调 SharedLinkService.enrich() 回填数据库
 *
 * 容错：OG 抓取或 DeepSeek 失败都不会阻塞流程；
 * 两个 Service 内部已降级，本 Worker 用 try/catch 包整体流程，
 * 确保 status 一定从 PENDING 推进到终态（否则链接永久卡在 PENDING 不可见）。
 */
@Component
public class SharedLinkEnrichmentWorker {

    private static final Logger log = LoggerFactory.getLogger(SharedLinkEnrichmentWorker.class);

    private final OgFetchService ogFetchService;
    private final OgFallbackService ogFallbackService;
    private final ClassificationService classificationService;
    private final SharedLinkService sharedLinkService;
    private final AlertWebhookClient alertWebhookClient;

    public SharedLinkEnrichmentWorker(OgFetchService ogFetchService,
                                      OgFallbackService ogFallbackService,
                                      ClassificationService classificationService,
                                      SharedLinkService sharedLinkService,
                                      AlertWebhookClient alertWebhookClient) {
        this.ogFetchService = ogFetchService;
        this.ogFallbackService = ogFallbackService;
        this.classificationService = classificationService;
        this.sharedLinkService = sharedLinkService;
        this.alertWebhookClient = alertWebhookClient;
    }

    /**
     * 异步富化入口：@Async 使本方法在 Spring 管理的线程池中执行。
     *
     * @param linkId 已落 PENDING 的链接 ID
     */
    @Async
    public void enrich(Long linkId) {
        log.info("enrichment 开始: linkId={}", linkId);
        try {
            doEnrich(linkId);
        } catch (Exception e) {
            // 兜底：即使未预料的异常也尝试将 status 推进到 PENDING_MANUAL，
            // 避免链接永久卡在 PENDING（对用户不可见，对管理员也查不到）
            log.error("enrichment 未捕获异常，尝试降级到 PENDING_MANUAL: linkId={} error={}",
                    linkId, e.getMessage(), e);
            tryFallbackStatus(linkId);
        }
    }

    /**
     * 核心富化逻辑，分步骤执行、每步独立容错。
     */
    private void doEnrich(Long linkId) {
        // 查找链接（理论上一定存在，若不存在说明并发删除，直接跳过）
        Optional<SharedLink> linkOpt = sharedLinkService.findById(linkId);
        if (linkOpt.isEmpty()) {
            log.warn("enrichment 跳过：链接不存在: linkId={}", linkId);
            return;
        }
        SharedLink link = linkOpt.get();
        String url = link.url();
        String host = link.host();

        // ── 步骤 1：OG 抓取 ──────────────────────────────────────────────
        OgFetchResult og;
        try {
            og = ogFetchService.fetch(url);
        } catch (Exception e) {
            // 防御性 catch：OgFetchService 内部已处理，正常不会到这里
            log.warn("enrichment OG 抓取异常（防御）: linkId={} error={}", linkId, e.getMessage());
            og = OgFetchResult.failure("抓取服务内部异常: " + e.getMessage());
        }

        // ── 步骤 1.5：OG 抓不到 title 时走 LLM 兜底猜一个 ──────────────────
        // 触发场景：PDF（arxiv pdf 直链）、反爬空响应（scholar 204）、防火墙拦截、
        // 微信公众号要 referer 等。LLM 根据 URL host/path 给个合理猜测，
        // 让 feed 卡片不至于一片空白。失败也无所谓——og 仍是空，跟以前一样。
        if (og.ogTitle() == null || og.ogTitle().isBlank()) {
            try {
                OgFallbackService.Guess guess = ogFallbackService.guess(url, host);
                if (!guess.isEmpty()) {
                    log.info("enrichment LLM 兜底 OG: linkId={} title={} reason={}",
                            linkId, guess.title(), og.errorMessage());
                    // 用兜底数据补 og（保留原 errorMessage 供排障）
                    og = new OgFetchResult(
                            guess.title(),
                            guess.description(),
                            og.ogCover(),
                            og.ogSiteName(),
                            og.errorMessage()
                    );
                }
            } catch (Exception e) {
                log.warn("enrichment OG 兜底异常（防御）: linkId={} error={}", linkId, e.getMessage());
            }
        }

        // ── 步骤 2：DeepSeek 分类 ────────────────────────────────────────
        ClassificationResult cls;
        try {
            cls = classificationService.classify(og.ogTitle(), og.ogDescription(), host);
        } catch (Exception e) {
            // 防御性 catch：ClassificationService 内部已处理
            log.warn("enrichment 分类异常（防御）: linkId={} error={}", linkId, e.getMessage());
            cls = ClassificationResult.fallback();
        }

        // ── 步骤 3：决定最终 status ──────────────────────────────────────
        // 简化策略（2026-04-24 起）：信任 AI 的"没命中 flag"判断，
        // 直接 APPROVED 不再区分白名单 / 非白名单。DomainWhitelist 保留但
        // 仅作为未来 fast-path（可跳过 AI 调用省 token），当前不参与状态决策。
        String finalStatus;
        if (cls.anyFlagSet()) {
            // 任一安全 flag 命中 → FLAGGED，进人工待审
            finalStatus = SharedLinkStatus.FLAGGED;
            log.info("enrichment 标记 FLAGGED: linkId={} nsfw={} ad={} flame={} illegal={} notResource={}",
                    linkId, cls.nsfw(), cls.ad(), cls.flame(), cls.illegal(), cls.notResource());
        } else {
            finalStatus = SharedLinkStatus.APPROVED;
            log.info("enrichment AI 放行 APPROVED: linkId={} host={}", linkId, host);
        }

        // ── 步骤 4：回填数据库 ───────────────────────────────────────────
        Map<String, Boolean> flags = Map.of(
                "nsfw",        cls.nsfw(),
                "ad",          cls.ad(),
                "flame",       cls.flame(),
                "illegal",     cls.illegal(),
                "notResource", cls.notResource()
        );

        sharedLinkService.enrich(
                linkId,
                og.ogTitle(),
                og.ogDescription(),
                og.ogCover(),
                og.ogSiteName(),
                og.errorMessage(),    // OG 失败时记录原因（供排障）
                cls.category(),
                flags,
                finalStatus
        );

        log.info("enrichment 完成: linkId={} status={} category={}", linkId, finalStatus, cls.category());

        // FLAGGED 状态即时告警：不等每日 digest，让管理员立刻看到 nsfw/ad/flame
        // 失败静默（webhook 挂了不能影响审核流转），所以写在 enrich 完成之后。
        if (SharedLinkStatus.FLAGGED.equals(finalStatus)) {
            sharedLinkService.findById(linkId).ifPresent(fresh -> alertWebhookClient.notifyFlagged(fresh, flags));
        }
    }

    /**
     * 兜底降级：未预料异常后尝试将 status 推进到 PENDING_MANUAL。
     * 避免链接永久卡在 PENDING（用户看不到自己的投稿，管理员也无法审核）。
     */
    private void tryFallbackStatus(Long linkId) {
        try {
            sharedLinkService.enrich(
                    linkId,
                    null, null, null, null,
                    "enrichment worker 未捕获异常，降级",
                    "other",
                    Map.of("nsfw", false, "ad", false, "flame", false, "illegal", false, "notResource", false),
                    SharedLinkStatus.PENDING_MANUAL
            );
            log.info("enrichment 降级完成: linkId={} -> PENDING_MANUAL", linkId);
        } catch (Exception ex) {
            log.error("enrichment 降级也失败，链接可能卡在 PENDING: linkId={} error={}",
                    linkId, ex.getMessage(), ex);
        }
    }
}
