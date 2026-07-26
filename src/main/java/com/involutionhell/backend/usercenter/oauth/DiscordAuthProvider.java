package com.involutionhell.backend.usercenter.oauth;

import com.alibaba.fastjson.JSONObject;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Discord 登录。JustAuth 1.16.6 没有内置 Discord source，自定义实现见
 * {@link DiscordAuthSource} / {@link AuthDiscordRequest}。
 */
@Component
public class DiscordAuthProvider implements AuthProvider {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public DiscordAuthProvider(
            @Value("${justauth.type.discord.client-id:}") String clientId,
            @Value("${justauth.type.discord.client-secret:}") String clientSecret,
            @Value("${justauth.type.discord.redirect-uri:}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public String key() {
        return "discord";
    }

    @Override
    public AuthRequest newRequest() {
        AuthProviders.requireConfigured(key(), clientId, clientSecret);
        return new AuthDiscordRequest(AuthConfig.builder()
                .clientId(clientId).clientSecret(clientSecret).redirectUri(redirectUri).build());
    }

    @Override
    public String redirectUri() {
        return redirectUri;
    }

    /** Discord /users/@me 的 "verified" 布尔表示该账号邮箱已验证。 */
    @Override
    public boolean isEmailVerified(AuthUser user) {
        JSONObject raw = user == null ? null : user.getRawUserInfo();
        return raw != null && raw.getBooleanValue("verified");
    }

    @Override
    public void revokeToken(AuthToken token) {
        if (token == null) {
            return;
        }
        ((AuthDiscordRequest) newRequest()).revokeToken(token);
    }
}
