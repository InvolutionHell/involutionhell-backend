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

    /** 仅改 status（管理员通过/拒绝、失效归档等）。 */
    void updateStatus(Long id, String status, String archivedReason);

    /** +1 report_count 并返回新值。3 及以上由 Service 层决定是否转 PENDING_MANUAL。 */
    int incrementReportCount(Long id);

    /** 限频判定：某用户在 since 之后提交了几条。 */
    int countBySubmitterSince(Long submitterId, java.time.Instant since);
}
