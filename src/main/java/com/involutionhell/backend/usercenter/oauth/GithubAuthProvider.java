package com.involutionhell.backend.usercenter.oauth;

import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** GitHub 登录。JustAuth 内置 source，直接包一层。 */
@Component
public class GithubAuthProvider implements AuthProvider {

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public GithubAuthProvider(
            @Value("${justauth.type.github.client-id:}") String clientId,
            @Value("${justauth.type.github.client-secret:}") String clientSecret,
            @Value("${justauth.type.github.redirect-uri:}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    public String key() {
        return "github";
    }

    @Override
    public AuthRequest newRequest() {
        AuthProviders.requireConfigured(key(), clientId, clientSecret);
        return new AuthGithubRequest(AuthConfig.builder()
                .clientId(clientId).clientSecret(clientSecret).redirectUri(redirectUri).build());
    }

    @Override
    public String redirectUri() {
        return redirectUri;
    }

    /** JustAuth 取的是 GitHub 的 primary email，而 GitHub 要求 primary 必须已验证，故信任。 */
    @Override
    public boolean isEmailVerified(AuthUser user) {
        return true;
    }
}
