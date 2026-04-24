package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.dto.AdminSummary;
import com.involutionhell.backend.community.model.LinkReport;
import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.repository.LinkReportRepository;
import com.involutionhell.backend.community.repository.SharedLinkRepository;
import com.involutionhell.backend.community.util.UrlNormalizer;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

/**
 * 社区分享链接业务服务。
 *
 * M1 只实现同步部分：
 * - submit：规范化 URL、限频、落 PENDING
 * - listApproved / listBySubmitter / listPendingForAdmin
 * - report：+1 计数，>=3 且当前 APPROVED 自动转 PENDING_MANUAL
 *
 * 异步 OG 抓取 + DeepSeek 分类在 M2/M3/M4 里接上；本类暴露 enrich() 入口供 worker 回填。
 */
@Service
public class SharedLinkService {

    private static final Logger log = LoggerFactory.getLogger(SharedLinkService.class);

    /** 每用户每 24h 最多提交 N 条。滚动时间窗，不是自然日。 */
    static final int DAILY_SUBMIT_LIMIT = 5;

    /** 3 条独立举报自动下架。 */
    static final int REPORT_THRESHOLD = 3;

    /** 桥接账号 username；见 schema.sql 里的 seed。submitInternal 走这个账号下单。 */
    private static final String BRIDGE_USERNAME = "discord-bridge";

    private final SharedLinkRepository linkRepo;
    private final LinkReportRepository reportRepo;
    private final UserAccountRepository userRepo;

    /**
     * 缓存 discord-bridge 账号 id，避免每次 submitInternal 都查一次库。
     * 仅用 volatile 做可见性保证（不是双检锁——没有 synchronized / AtomicReference
     * 的原子 compare-and-set）。之所以不强依赖启动期一次性初始化，是因为启动时
     * DB 可能还没 ready（SPRING_SQL_INIT_MODE=always 会先跑 schema.sql seed），
     * @PostConstruct 里一次性 lookup 最稳；若 seed 来得更晚，submitInternal 里
     * 做兜底查询。并发竞争时最差只是多查一次 DB（幂等），不会写坏。
     */
    private volatile Long bridgeId;

    /**
     * 用 @Lazy 打破循环依赖：Worker → Service → Worker。
     * Worker 在 submit() 成功后被调用，@Lazy 确保 Spring 容器初始化顺序无冲突。
     */
    private SharedLinkEnrichmentWorker enrichmentWorker;

    public SharedLinkService(SharedLinkRepository linkRepo,
                             LinkReportRepository reportRepo,
                             UserAccountRepository userRepo) {
        this.linkRepo = linkRepo;
        this.reportRepo = reportRepo;
        this.userRepo = userRepo;
    }

    @Autowired
    @Lazy
    public void setEnrichmentWorker(SharedLinkEnrichmentWorker enrichmentWorker) {
        this.enrichmentWorker = enrichmentWorker;
    }

    /**
     * 启动时一次性解析 bridge 账号 id。
     * 首次 seed 未落库（bootstrap 顺序问题）时只打 warn、不抛，让 Spring 正常起；
     * 真正的 submitInternal 调用会再查一次兜底。
     */
    @PostConstruct
    void resolveBridgeId() {
        userRepo.findByUsername(BRIDGE_USERNAME)
                .map(UserAccount::id)
                .ifPresentOrElse(
                        id -> {
                            this.bridgeId = id;
                            log.info("discord-bridge id resolved: {}", id);
                        },
                        () -> log.warn(
                                "discord-bridge 账号未找到（首次部署 seed 可能稍晚到达），"
                                        + "submitInternal 首次调用会延迟解析"));
    }

    /**
     * 提交分享链接。
     *
     * @throws IllegalArgumentException URL 非法
     * @throws DuplicateKeyException    url_hash 重复（同一 URL 已存在）
     * @throws RateLimitExceeded        当前用户 24h 内已达上限
     */
    public SharedLink submit(Long submitterId, String rawUrl, String recommendation) {
        UrlNormalizer.Normalized norm = UrlNormalizer.normalize(rawUrl);

        // 限频：滚动 24h
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        int already = linkRepo.countBySubmitterSince(submitterId, since);
        if (already >= DAILY_SUBMIT_LIMIT) {
            throw new RateLimitExceeded(
                    "daily submit limit reached: " + DAILY_SUBMIT_LIMIT);
        }

        String urlHash = UrlNormalizer.sha256Hex(norm.canonicalUrl());

        // 先查去重：若已存在直接抛 DuplicateKeyException 语义错误
        Optional<SharedLink> existing = linkRepo.findByUrlHash(urlHash);
        if (existing.isPresent()) {
            throw new DuplicateKeyException(
                    "url already submitted: id=" + existing.get().id());
        }

        SharedLink draft = new SharedLink(
                null,                   // id
                submitterId,
                norm.canonicalUrl(),
                urlHash,
                norm.host(),
                recommendation,
                null, null, null, null, // og fields
                null,                   // og fetch error
                null,                   // category
                new HashMap<>(),        // flags
                SharedLinkStatus.PENDING,
                0,                      // report_count
                null, null,             // archived
                null, null              // created/updated
        );
        SharedLink saved = linkRepo.insert(draft);
        log.info("shared-link submitted: id={} submitter={} host={}",
                saved.id(), submitterId, saved.host());

        // 触发异步富化（OG 抓取 + DeepSeek 分类），不阻塞当前 HTTP 响应
        if (enrichmentWorker != null) {
            enrichmentWorker.enrich(saved.id());
        }

        return saved;
    }

    /**
     * 内部桥接路径：给 Discord Bot / 未来其它机器人渠道用。
     *
     * 与 submit() 的差异：
     * - 不做 24h 限频（机器人是整群一把提，限频会把合法流量卡死）
     * - 不关心前端登录态：submitter 固定取 seed 的 discord-bridge 账号
     * - recommendation 前缀自动打「来自 Discord @{label}」，前端展示能看到真实分享人
     *
     * 与 submit() 一致的：
     * - 同样走 UrlNormalizer、去重、OG + DeepSeek 异步富化
     * - 同样的状态机（PENDING → APPROVED/PENDING_MANUAL/FLAGGED）
     * - 同样的 DuplicateKeyException 语义
     */
    public SharedLink submitInternal(String submitterLabel, String rawUrl, String recommendation) {
        UrlNormalizer.Normalized norm = UrlNormalizer.normalize(rawUrl);

        Long resolvedBridgeId = bridgeId;
        if (resolvedBridgeId == null) {
            // @PostConstruct 启动时没解析到（seed 还没跑），这里最后兜底再查一次
            resolvedBridgeId = userRepo.findByUsername(BRIDGE_USERNAME)
                    .map(UserAccount::id)
                    .orElseThrow(() -> new IllegalStateException(
                            "discord-bridge 账号不存在，检查 schema.sql 是否已执行 seed"));
            this.bridgeId = resolvedBridgeId;
        }

        String urlHash = UrlNormalizer.sha256Hex(norm.canonicalUrl());

        // 同样的去重：同一 URL 全站只留一条
        Optional<SharedLink> existing = linkRepo.findByUrlHash(urlHash);
        if (existing.isPresent()) {
            throw new DuplicateKeyException(
                    "url already submitted: id=" + existing.get().id());
        }

        String combinedRec = buildBridgeRecommendation(submitterLabel, recommendation);

        SharedLink draft = new SharedLink(
                null,
                resolvedBridgeId,
                norm.canonicalUrl(),
                urlHash,
                norm.host(),
                combinedRec,
                null, null, null, null,
                null,
                null,
                new HashMap<>(),
                SharedLinkStatus.PENDING,
                0,
                null, null,
                null, null
        );
        SharedLink saved = linkRepo.insert(draft);
        log.info("shared-link submitted via bridge: id={} label={} host={}",
                saved.id(), submitterLabel, saved.host());

        if (enrichmentWorker != null) {
            enrichmentWorker.enrich(saved.id());
        }
        return saved;
    }

    /**
     * 把原 recommendation 前面拼上「来自 Discord @label：」。
     * label 为空时降级为「来自 Discord：」；原 rec 为空时只留前缀。
     */
    private static String buildBridgeRecommendation(String submitterLabel, String original) {
        String prefix = (submitterLabel == null || submitterLabel.isBlank())
                ? "来自 Discord"
                : "来自 Discord @" + submitterLabel;
        if (original == null || original.isBlank()) {
            return prefix;
        }
        return prefix + "：" + original;
    }

    public Optional<SharedLink> findById(Long id) {
        return linkRepo.findById(id);
    }

    /**
     * 审核摘要：给 ChatBot 每日 digest 用。
     *
     * @param sampleLimit PENDING_MANUAL 采样条数（展示最早 N 条），传 <=0 时不采样
     */
    public AdminSummary buildAdminSummary(int sampleLimit) {
        int pendingManual = linkRepo.countByStatus(SharedLinkStatus.PENDING_MANUAL);
        int flagged = linkRepo.countByStatus(SharedLinkStatus.FLAGGED);
        int approvedLast24h = linkRepo.countByStatusSince(
                SharedLinkStatus.APPROVED,
                Instant.now().minus(1, ChronoUnit.DAYS));

        List<AdminSummary.Sample> samples = List.of();
        if (sampleLimit > 0 && pendingManual > 0) {
            samples = linkRepo.findPendingForAdmin().stream()
                    .filter(l -> SharedLinkStatus.PENDING_MANUAL.equals(l.status()))
                    .limit(sampleLimit)
                    .map(l -> new AdminSummary.Sample(l.id(), l.host(), l.url()))
                    .toList();
        }
        return new AdminSummary(pendingManual, flagged, approvedLast24h, samples);
    }

    public List<SharedLink> listApproved(String category, int limit, int offset) {
        return linkRepo.findApproved(category, limit, offset);
    }

    public List<SharedLink> listBySubmitter(Long submitterId) {
        return linkRepo.findBySubmitter(submitterId);
    }

    public List<SharedLink> listPendingForAdmin() {
        return linkRepo.findPendingForAdmin();
    }

    /**
     * 举报。同一人重复举报同一条（DB UNIQUE）静默成功、不计数。
     *
     * @return true = 此次举报触发了自动下架
     */
    public boolean report(Long linkId, Long reporterId, String reason) {
        LinkReport draft = new LinkReport(null, linkId, reporterId, reason, null);
        try {
            reportRepo.insert(draft);
        } catch (DuplicateKeyException e) {
            return false;
        }
        int total = linkRepo.incrementReportCount(linkId);

        // 达阈值且当前 APPROVED，自动转 PENDING_MANUAL
        if (total >= REPORT_THRESHOLD) {
            Optional<SharedLink> link = linkRepo.findById(linkId);
            if (link.isPresent() && SharedLinkStatus.APPROVED.equals(link.get().status())) {
                linkRepo.transitionStatus(linkId, SharedLinkStatus.PENDING_MANUAL, null);
                log.info("shared-link auto-demoted to PENDING_MANUAL by reports: id={}", linkId);
                return true;
            }
        }
        return false;
    }

    /**
     * 异步 worker 回填 OG + 分类 + 安全判定。
     * M4 里调：提交后事件驱动或 @Async。
     */
    public void enrich(Long id,
                       String ogTitle, String ogDescription,
                       String ogCover, String ogSiteName, String ogFetchError,
                       String category, java.util.Map<String, Boolean> flags,
                       String finalStatus) {
        linkRepo.updateEnrichment(id,
                ogTitle, ogDescription, ogCover, ogSiteName, ogFetchError,
                category, flags, finalStatus);
    }

    /** 限频异常；Controller 侧转成 429。 */
    public static class RateLimitExceeded extends RuntimeException {
        public RateLimitExceeded(String msg) { super(msg); }
    }
}
