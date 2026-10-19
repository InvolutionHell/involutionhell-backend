package com.involutionhell.backend.usercenter.oauth;

import com.alibaba.fastjson.JSONObject;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthGoogleRequest;
import me.zhyd.oauth.request.AuthRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google 登录。JustAuth 内置 source：默认 scope openid/email/profile，资料取自
 * /oauth2/v3/userinfo，uuid 是 OIDC 的 sub（稳定、不随邮箱变）。
 */
@Component
public class GoogleAuthProvider implements AuthProvider {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GoogleAuthProvider(
            @Value("${justauth.type.google.client-id:}") String clientId,
            @Value("${justauth.type.google.client-secret:}") String clientSecret,
            @Value("${justauth.type.google.redirect-uri:}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public String key() {
        return "google";
    }

    @Override
    public boolean isConfigured() {
        return AuthProviders.isConfigured(clientId, clientSecret);
    }

    @Override
    public AuthRequest newRequest() {
        AuthProviders.requireConfigured(key(), clientId, clientSecret);
        return new AuthGoogleRequest(AuthConfig.builder()
                .clientId(clientId).clientSecret(clientSecret).redirectUri(redirectUri).build());
    }

    @Override
    public String redirectUri() {
        return redirectUri;
    }

    /** userinfo 的 email_verified 为 true 才算已验证（OIDC 标准 claim）；缺失按未验证。 */
    @Override
    public boolean isEmailVerified(AuthUser user) {
        JSONObject raw = user == null ? null : user.getRawUserInfo();
        return raw != null && raw.getBooleanValue("email_verified");
    }
}
