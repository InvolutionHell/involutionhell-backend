package com.involutionhell.backend.sso.dto;

/**
 * POST /oauth/sso/code 的请求体，由 IH 前端 /sso/authorize 页代当前登录用户发起。
 * 校验规则见 SsoService#issueCode。
 */
public record SsoCodeRequest(
        String clientId,
        String redirectUri,
        String state,
        String codeChallenge,
        String codeChallengeMethod
) {
}
