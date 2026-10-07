package com.involutionhell.backend.sso.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.sso.dto.SsoCodeRequest;
import com.involutionhell.backend.sso.dto.SsoTokenRequest;
import com.involutionhell.backend.sso.dto.SsoUserInfo;
import com.involutionhell.backend.sso.service.SsoService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * IH 通行证（INV-010）的两个接口：
 * <ol>
 *   <li>IH 前端 /sso/authorize 页代已登录用户调 {@code /oauth/sso/code} 签码，浏览器带着码跳回 client；</li>
 *   <li>client 的服务端拿码调 {@code /internal/sso/token} 换用户资料，之后发它自己的会话。</li>
 * </ol>
 * IH 的 satoken 始终不出 IH。
 */
@RestController
public class SsoController {

    private final SsoService ssoService;

    public SsoController(SsoService ssoService) {
        this.ssoService = ssoService;
    }

    /**
     * 签码，需要登录。SaTokenConfigure 的全局拦截已经要求登录，这里再挂一道注解，
     * 防止以后有人把 /oauth/** 整个放行。
     */
    @SaCheckLogin
    @PostMapping("/oauth/sso/code")
    public ApiResponse<Map<String, String>> issueCode(@RequestBody SsoCodeRequest request) {
        String redirect = ssoService.issueCode(StpUtil.getLoginIdAsLong(), request);
        return ApiResponse.ok(Map.of("redirect", redirect));
    }

    /**
     * 换码。调用方是 client 的服务端，没有 IH 登录态，所以在 SaTokenConfigure 里放行，
     * 由这里用 client secret 鉴权。Caddy 会把这条路径转给后端，公网也能打到，secret 是唯一的门。
     */
    @PostMapping("/internal/sso/token")
    public ResponseEntity<ApiResponse<SsoUserInfo>> token(@RequestBody SsoTokenRequest request) {
        if (!ssoService.authenticateClient(request.clientId(), request.clientSecret())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponse<>(false, "invalid_client", null));
        }
        return ssoService.redeem(request)
                .map(user -> ResponseEntity.ok(ApiResponse.ok(user)))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(new ApiResponse<>(false, "invalid_grant", null)));
    }
}
