package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.util.DomainWhitelist;
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
    private final ClassificationService classificationService;
    private final SharedLinkService sharedLinkService;

    public SharedLinkEnrichmentWorker(OgFetchService ogFetchService,
                                      ClassificationService classificationService,
                                      SharedLinkService sharedLinkService) {
        this.ogFetchService = ogFetchService;
        this.classificationService = classificationService;
        this.sharedLinkService = sharedLinkService;
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
        String finalStatus;
        if (cls.anyFlagSet()) {
            // 任一安全 flag 命中 → FLAGGED，进人工待审
            finalStatus = SharedLinkStatus.FLAGGED;
            log.info("enrichment 标记 FLAGGED: linkId={} nsfw={} ad={} flame={}",
                    linkId, cls.nsfw(), cls.ad(), cls.flame());
        } else if (DomainWhitelist.contains(host)) {
            // 白名单域名 + 无安全问题 → 直接 APPROVED
            finalStatus = SharedLinkStatus.APPROVED;
            log.info("enrichment 白名单 APPROVED: linkId={} host={}", linkId, host);
        } else {
            // 非白名单域名 → PENDING_MANUAL，等管理员人工审核
            finalStatus = SharedLinkStatus.PENDING_MANUAL;
            log.info("enrichment 非白名单 PENDING_MANUAL: linkId={} host={}", linkId, host);
        }

        // ── 步骤 4：回填数据库 ───────────────────────────────────────────
        Map<String, Boolean> flags = Map.of(
                "nsfw", cls.nsfw(),
                "ad", cls.ad(),
                "flame", cls.flame()
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
                    Map.of("nsfw", false, "ad", false, "flame", false),
                    SharedLinkStatus.PENDING_MANUAL
            );
            log.info("enrichment 降级完成: linkId={} -> PENDING_MANUAL", linkId);
        } catch (Exception ex) {
            log.error("enrichment 降级也失败，链接可能卡在 PENDING: linkId={} error={}",
                    linkId, ex.getMessage(), ex);
        }
    }
}
