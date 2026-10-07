package com.involutionhell.backend.sso.dto;

/**
 * POST /internal/sso/token 的请求体，由 client 的服务端（HoloCard）发起，不经过浏览器。
 */
public record SsoTokenRequest(
        String clientId,
        String clientSecret,
        String code,
        String codeVerifier,
        String redirectUri
) {
}
