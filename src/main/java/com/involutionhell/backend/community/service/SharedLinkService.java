package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.LinkReport;
import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.repository.LinkReportRepository;
import com.involutionhell.backend.community.repository.SharedLinkRepository;
import com.involutionhell.backend.community.util.UrlNormalizer;
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

    private final SharedLinkRepository linkRepo;
    private final LinkReportRepository reportRepo;

    /**
     * 用 @Lazy 打破循环依赖：Worker → Service → Worker。
     * Worker 在 submit() 成功后被调用，@Lazy 确保 Spring 容器初始化顺序无冲突。
     */
    private SharedLinkEnrichmentWorker enrichmentWorker;

    public SharedLinkService(SharedLinkRepository linkRepo, LinkReportRepository reportRepo) {
        this.linkRepo = linkRepo;
        this.reportRepo = reportRepo;
    }

    @Autowired
    @Lazy
    public void setEnrichmentWorker(SharedLinkEnrichmentWorker enrichmentWorker) {
        this.enrichmentWorker = enrichmentWorker;
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

    public Optional<SharedLink> findById(Long id) {
        return linkRepo.findById(id);
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
                linkRepo.updateStatus(linkId, SharedLinkStatus.PENDING_MANUAL, null);
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
