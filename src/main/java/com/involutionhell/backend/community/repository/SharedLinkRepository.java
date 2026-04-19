package com.involutionhell.backend.community.repository;

import com.involutionhell.backend.community.model.SharedLink;

import java.util.List;
import java.util.Optional;

/**
 * shared_links 仓库接口。M1 只落骨架，实现留给 JdbcSharedLinkRepository。
 *
 * 查询方法都按 status 分维度提供，避免 Service 层散落 status 字符串。
 */
public interface SharedLinkRepository {

    SharedLink insert(SharedLink draft);

    Optional<SharedLink> findById(Long id);

    Optional<SharedLink> findByUrlHash(String urlHash);

    /** 公开 /feed 列表（status = APPROVED），按 created_at DESC。 */
    List<SharedLink> findApproved(String category, int limit, int offset);

    /** 当前用户自己的所有分享（全状态），用于 /u/[userId]/shares。 */
    List<SharedLink> findBySubmitter(Long submitterId);

    /** 管理员待审：PENDING_MANUAL + FLAGGED。 */
    List<SharedLink> findPendingForAdmin();

    /** 更新 OG + 分类 + flags + status（异步 worker 跑完调）。 */
    void updateEnrichment(Long id,
                          String ogTitle, String ogDescription,
                          String ogCover, String ogSiteName, String ogFetchError,
                          String category, java.util.Map<String, Boolean> flags,
                          String status);

    /**
     * 通用状态迁移（admin approve/reject / 举报自动降级等）。
     * adminNote 可空；非空时落到 admin_note 列。**不触**动 archived_at/archived_reason。
     * ARCHIVED 状态请走 {@link #archive}。
     */
    void transitionStatus(Long id, String status, String adminNote);

    /** 归档失效链接：落 status=ARCHIVED + archived_at=NOW + archived_reason。 */
    void archive(Long id, String archivedReason);

    /** +1 report_count 并返回新值。3 及以上由 Service 层决定是否转 PENDING_MANUAL。 */
    int incrementReportCount(Long id);

    /** 限频判定：某用户在 since 之后提交了几条。 */
    int countBySubmitterSince(Long submitterId, java.time.Instant since);

    /** M9 失效探活：拉所有 APPROVED 的 (id, url)，用于 HEAD 探活。只拿需要的两列。 */
    List<ProbeTarget> findApprovedForProbe(int limit);

    /** 探活失败 +1，返回新值。达阈值（>=2）由 Job 层决定是否 ARCHIVED。 */
    int incrementProbeFail(Long id);

    /** 探活成功归零。 */
    void resetProbeFail(Long id);

    /** 探活后记录时间，避免频繁扫相同 link。 */
    void touchProbeLastAt(Long id);

    /** 探活扫描需要的最小字段集合。 */
    record ProbeTarget(Long id, String url, int probeFailCount) {}
}
