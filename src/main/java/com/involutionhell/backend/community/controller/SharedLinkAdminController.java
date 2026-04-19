package com.involutionhell.backend.community.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.community.dto.SharedLinkView;
import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
import com.involutionhell.backend.community.repository.SharedLinkRepository;
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

    public SharedLinkAdminController(SharedLinkService service,
                                     SharedLinkRepository linkRepo) {
        this.service = service;
        this.linkRepo = linkRepo;
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
        linkRepo.updateStatus(id, SharedLinkStatus.APPROVED, null);
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
        linkRepo.updateStatus(id, SharedLinkStatus.REJECTED, reason);
        log.info("admin reject shared-link id={} reason={}", id, reason);
        return linkRepo.findById(id)
                .map(link -> ResponseEntity.ok(ApiResponse.ok(SharedLinkView.from(link))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ApiResponse<>(false, "link disappeared after update", null)));
    }
}
