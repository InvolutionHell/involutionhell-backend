package com.involutionhell.backend.community.controller;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.community.dto.AdminSummary;
import com.involutionhell.backend.community.dto.InternalShareRequest;
import com.involutionhell.backend.community.dto.SharedLinkView;
import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.service.SharedLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机器人桥接渠道的提交接口。
 *
 * 设计目标：
 * - 部署在 127.0.0.1:8080，不走 Caddy（Caddyfile 不代理 /internal 路径）
 * - 仅接受带正确 X-Internal-Key 的请求，密钥通过 env INTERNAL_API_KEY 注入
 * - 用 SharedLinkService.submitInternal，跳过 24h 限频，固定挂到 discord-bridge 系统账号
 *
 * 路径选 /api/community/links/internal 而非独立 /internal/... 是为了：
 * - 沿用已有 SharedLinkController 的业务命名，读代码更顺手
 * - SaTokenConfigure 放行这条 path（无登录态）
 */
@RestController
@RequestMapping("/api/community/links/internal")
public class SharedLinkInternalController {

    private static final Logger log = LoggerFactory.getLogger(SharedLinkInternalController.class);

    private final SharedLinkService service;
    private final String expectedKey;

    public SharedLinkInternalController(
            SharedLinkService service,
            @Value("${internal.api-key:}") String expectedKey) {
        this.service = service;
        this.expectedKey = expectedKey;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SharedLinkView>> submit(
            @RequestHeader(value = "X-Internal-Key", required = false) String providedKey,
            @RequestBody InternalShareRequest req) {

        // 未配置 key 时拒绝所有请求（防止开发环境忘设 key 就上线导致接口裸奔）
        if (expectedKey == null || expectedKey.isBlank()) {
            log.error("internal.api-key 未配置，拒绝请求");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResponse<>(false, "internal api not configured", null));
        }
        if (providedKey == null || !expectedKey.equals(providedKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiResponse<>(false, "invalid internal key", null));
        }

        if (req == null || req.url() == null || req.url().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(false, "url is required", null));
        }

        try {
            SharedLink saved = service.submitInternal(
                    req.submitterLabel(), req.url(), req.recommendation());
            return ResponseEntity.ok(ApiResponse.ok(SharedLinkView.from(saved)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(false, e.getMessage(), null));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiResponse<>(false, "url already submitted", null));
        } catch (IllegalStateException e) {
            // discord-bridge 账号不存在等运行时条件错误
            log.error("internal submit 运行时错误: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse<>(false, e.getMessage(), null));
        }
    }

    /**
     * 审核队列摘要，给 ChatBot 每日 digest 推送用。
     * 同样走 X-Internal-Key 鉴权，不要求 sa-token 登录态。
     *
     * @param providedKey header: X-Internal-Key
     * @param sampleLimit 查询参数：要带几条 PENDING_MANUAL 示例链接（默认 5，最多 20）
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AdminSummary>> summary(
            @RequestHeader(value = "X-Internal-Key", required = false) String providedKey,
            @RequestParam(value = "sampleLimit", defaultValue = "5") int sampleLimit) {

        if (expectedKey == null || expectedKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResponse<>(false, "internal api not configured", null));
        }
        if (providedKey == null || !expectedKey.equals(providedKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiResponse<>(false, "invalid internal key", null));
        }

        int safeSample = Math.max(0, Math.min(sampleLimit, 20));
        AdminSummary s = service.buildAdminSummary(safeSample);
        return ResponseEntity.ok(ApiResponse.ok(s));
    }

    /**
     * 按 id 拉取单条分享，供 Bot 轮询异步 enrichment 的最终状态。
     * SharedLinkView 已经屏蔽了敏感字段（不含 submitter_id 明文等），可安全给 Bot 回显。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SharedLinkView>> getById(
            @RequestHeader(value = "X-Internal-Key", required = false) String providedKey,
            @PathVariable Long id) {

        if (expectedKey == null || expectedKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResponse<>(false, "internal api not configured", null));
        }
        if (providedKey == null || !expectedKey.equals(providedKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiResponse<>(false, "invalid internal key", null));
        }

        return service.findById(id)
                .map(link -> ResponseEntity.ok(ApiResponse.ok(SharedLinkView.from(link))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ApiResponse<>(false, "not found", null)));
    }
}
