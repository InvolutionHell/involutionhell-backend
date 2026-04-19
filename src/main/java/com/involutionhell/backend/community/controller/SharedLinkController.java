package com.involutionhell.backend.community.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.community.dto.SharedLinkRequest;
import com.involutionhell.backend.community.dto.SharedLinkView;
import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.service.SharedLinkService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公开/登录接口：
 * - GET  /api/community/links                 公开列表（匿名可访问，仅 APPROVED）
 * - POST /api/community/links                 提交链接（需登录）
 * - POST /api/community/links/{id}/report     举报（需登录）
 * - GET  /api/community/links/mine            我提交的所有链接（需登录）
 *
 * 公开读放行在 SaTokenConfigure 里配 /api/community/links 与 /api/community/links/*
 * （对 POST 的写接口由方法级 @SaCheckLogin 兜底）。
 */
@RestController
@RequestMapping("/api/community/links")
public class SharedLinkController {

    private final SharedLinkService service;

    public SharedLinkController(SharedLinkService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<SharedLinkView>> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        List<SharedLinkView> views = service.listApproved(category, safeLimit, safeOffset)
                .stream().map(SharedLinkView::from).toList();
        return ApiResponse.ok(views);
    }

    @PostMapping
    @SaCheckLogin
    public ResponseEntity<ApiResponse<SharedLinkView>> submit(@RequestBody SharedLinkRequest req) {
        if (req == null || req.url() == null || req.url().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(false, "url is required", null));
        }
        long uid = StpUtil.getLoginIdAsLong();
        try {
            SharedLink saved = service.submit(uid, req.url(), req.recommendation());
            return ResponseEntity.ok(ApiResponse.ok(SharedLinkView.from(saved)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(false, e.getMessage(), null));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse<>(false, "url already submitted", null));
        } catch (SharedLinkService.RateLimitExceeded e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ApiResponse<>(false, e.getMessage(), null));
        }
    }

    @PostMapping("/{id}/report")
    @SaCheckLogin
    public ApiResponse<Map<String, Object>> report(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        long uid = StpUtil.getLoginIdAsLong();
        String reason = body != null ? body.getOrDefault("reason", null) : null;
        boolean demoted = service.report(id, uid, reason);
        Map<String, Object> res = new HashMap<>();
        res.put("demoted", demoted);
        return ApiResponse.ok(res);
    }

    @GetMapping("/mine")
    @SaCheckLogin
    public ApiResponse<List<SharedLinkView>> mine() {
        long uid = StpUtil.getLoginIdAsLong();
        List<SharedLinkView> views = service.listBySubmitter(uid)
                .stream().map(SharedLinkView::from).toList();
        return ApiResponse.ok(views);
    }
}
