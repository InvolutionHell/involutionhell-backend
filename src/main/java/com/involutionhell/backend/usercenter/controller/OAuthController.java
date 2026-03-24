package com.involutionhell.backend.usercenter.controller;

import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.service.AuthService;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    @Value("${justauth.type.github.client-id}")
    private String githubClientId;

    @Value("${justauth.type.github.client-secret}")
    private String githubClientSecret;

    @Value("${justauth.type.github.redirect-uri}")
    private String githubRedirectUri;

    @Value("${AUTH_URL:http://localhost:3000}")
    private String frontEndUrl;

    // 注入认证服务，用于查询/注册用户并执行 Sa-Token 登录
    private final AuthService authService;

    public OAuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 获取 GitHub 授权请求对象
     */
    private AuthRequest getAuthRequest() {
        return new AuthGithubRequest(AuthConfig.builder()
                .clientId(githubClientId)
                .clientSecret(githubClientSecret)
                .redirectUri(githubRedirectUri)
                .build());
    }

    /**
     * 构建授权链接并重定向到 GitHub
     * 前端直接跳转到后端此地址（NEXT_PUBLIC_BACKEND_URL + /oauth/render/github）发起登录
     */
    @GetMapping("/oauth/render/github")
    public void renderAuth(HttpServletResponse response) throws IOException {
        // 打印当前使用的 GitHub Client ID 和 redirect_uri，便于排查 token 配置问题
        log.info("[OAuth] GitHub Client ID = {}, redirect_uri = {}", githubClientId, githubRedirectUri);
        AuthRequest authRequest = getAuthRequest();
        response.sendRedirect(authRequest.authorize(me.zhyd.oauth.utils.AuthStateUtils.createState()));
    }

    /**
     * GitHub OAuth 回调地址，路径与 GitHub OAuth App 注册保持一致（/api/auth/callback/github）
     * GitHub → localhost:3000/api/auth/callback/github → Next.js rewrite → localhost:8080/api/auth/callback/github
     */
    @GetMapping("/api/auth/callback/github")
    public void login(AuthCallback callback, HttpServletResponse response) throws IOException {
        AuthRequest authRequest = getAuthRequest();
        AuthResponse<?> authResponse = authRequest.login(callback);
        
        if (authResponse.ok()) {
            AuthUser authUser = (AuthUser) authResponse.getData();

            // 调用 AuthService.loginByGithub()：查询或自动注册用户，然后执行 Sa-Token 登录
            // 返回的 LoginResponse 包含 tokenName、tokenValue 和用户视图
            LoginResponse loginResponse = authService.loginByGithub(authUser);

            // 登录成功后重定向到前端，将 token 作为 URL 参数传给前端
            // 前端读取 ?token= 参数后存入 localStorage，并清除 URL 中的参数
            String redirectUrl = frontEndUrl + "/?token=" + loginResponse.tokenValue();
            response.sendRedirect(redirectUrl);
        } else {
            // 登录失败，重定向回前端并带上错误信息
            response.sendRedirect(frontEndUrl + "/login?error=oauth_failed");
        }
    }
}