package com.involutionhell.backend.community.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.community.dto.SharedLinkView;
import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.repository.SharedLinkRepository;
import com.involutionhell.backend.community.service.SharedLinkEnrichmentWorker;
import com.involutionhell.backend.community.service.SharedLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 社区分享链接 admin 接口。所有端点均要求 admin 角色。
 *
 * 设计：
 * - GET   /api/admin/community/pending          待审列表（PENDING_MANUAL + FLAGGED）
 * - POST  /api/admin/community/{id}/approve     通过 → APPROVED
 * - POST  /api/admin/community/{id}/reject      拒绝 → REJECTED，可附 reason
 *
 * 这个 Controller 直接依赖 Repository 做状态变更（通过/拒绝是单纯的 status 切换，
 * 不需要走 Service 的业务编排），和 EventAdminController 一样。
 *
 * 若将来需要审核日志 / 操作者 ID 绑定，再把状态变更逻辑挪到 Service 层。
 */
@RestController
@RequestMapping("/api/admin/community")
@SaCheckRole("admin")
public class SharedLinkAdminController {

    private static final Logger log = LoggerFactory.getLogger(SharedLinkAdminController.class);

    private final SharedLinkService service;
    private final SharedLinkRepository linkRepo;
    private final SharedLinkEnrichmentWorker enrichmentWorker;

    public SharedLinkAdminController(SharedLinkService service,
                                     SharedLinkRepository linkRepo,
                                     SharedLinkEnrichmentWorker enrichmentWorker) {
        this.service = service;
        this.linkRepo = linkRepo;
        this.enrichmentWorker = enrichmentWorker;
    }

    @GetMapping("/pending")
    public ApiResponse<List<SharedLinkView>> listPending() {
        List<SharedLinkView> views = service.listPendingForAdmin()
                .stream().map(SharedLinkView::from).toList();
        return ApiResponse.ok(views);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<SharedLinkView>> approve(@PathVariable Long id) {
        Optional<SharedLink> maybe = linkRepo.findById(id);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse<>(false, "link not found", null));
        }
        linkRepo.transitionStatus(id, SharedLinkStatus.APPROVED, null);
        log.info("admin approve shared-link id={}", id);
        return linkRepo.findById(id)
                .map(link -> ResponseEntity.ok(ApiResponse.ok(SharedLinkView.from(link))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ApiResponse<>(false, "link disappeared after update", null)));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<SharedLinkView>> reject(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        Optional<SharedLink> maybe = linkRepo.findById(id);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse<>(false, "link not found", null));
        }
        String reason = body != null ? body.getOrDefault("reason", null) : null;
        // reason 现在落到 admin_note 列（之前 updateStatus 在非 ARCHIVED 时会静默丢弃）
        linkRepo.transitionStatus(id, SharedLinkStatus.REJECTED, reason);
        log.info("admin reject shared-link id={} reason={}", id, reason);
        return linkRepo.findById(id)
                .map(link -> ResponseEntity.ok(ApiResponse.ok(SharedLinkView.from(link))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ApiResponse<>(false, "link disappeared after update", null)));
    }

    /**
     * 重跑 enrichment（OG 抓取 + LLM 兜底 + 分类）。
     *
     * 用途：
     * - OG 抓取规则升级后回填历史链接
     * - 单条链接首次抓取卡在限流 / 反爬，等过段时间手动重试
     * - 测试新 SiteAdapter / OgFallback 效果
     *
     * 异步触发，不等结果——立即返回 202 Accepted，前端轮询 /pending 看新数据。
     * status 不变（不会把 APPROVED 推回 PENDING），enrich() 内部会原地覆盖 og_* 字段。
     */
    @PostMapping("/{id}/refetch-og")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refetchOg(@PathVariable Long id) {
        Optional<SharedLink> maybe = linkRepo.findById(id);
        if (maybe.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse<>(false, "link not found", null));
        }
        log.info("admin refetch-og shared-link id={}", id);
        enrichmentWorker.enrich(id);
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(Map.of("id", id, "queued", true)));
    }

    /**
     * 批量重跑 enrichment。POST body: {"ids": [1, 2, 3]}。
     * 用 ids: ["all"] 表示对全表扫描所有 og_title IS NULL 的链接重跑（运维用）。
     *
     * 防误操作：单次最多 100 条；"all" 也走相同上限。
     */
    @PostMapping("/refetch-og/bulk")
    public ResponseEntity<ApiResponse<Map<String, Object>>> bulkRefetchOg(
            @RequestBody Map<String, Object> body) {
        Object idsRaw = body.get("ids");
        List<Long> ids;
        if (idsRaw instanceof List<?> raw && raw.size() == 1 && "all".equals(raw.get(0))) {
            ids = service.findIdsMissingOg(100);
        } else if (idsRaw instanceof List<?> raw) {
            ids = raw.stream()
                    .map(v -> v instanceof Number n ? n.longValue() : Long.parseLong(v.toString()))
                    .limit(100)
                    .toList();
        } else {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(false, "missing or invalid 'ids' field", null));
        }
        log.info("admin bulk refetch-og count={}", ids.size());
        for (Long id : ids) {
            enrichmentWorker.enrich(id);
        }
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(Map.of("queued", ids.size(), "ids", ids)));
    }
}
