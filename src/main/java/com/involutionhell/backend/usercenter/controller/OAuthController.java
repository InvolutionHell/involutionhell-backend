package com.involutionhell.backend.usercenter.controller;

import cn.dev33.satoken.stp.StpUtil;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

    @Value("${justauth.type.github.client-id}")
    private String githubClientId;

    @Value("${justauth.type.github.client-secret}")
    private String githubClientSecret;

    @Value("${justauth.type.github.redirect-uri}")
    private String githubRedirectUri;

    @Value("${AUTH_URL:http://localhost:3000}")
    private String frontEndUrl;

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
     * 构建授权链接并重定向到第三方平台
     * 前端通过直接访问此接口（如：a 标签 href）来发起登录
     */
    @GetMapping("/render/github")
    public void renderAuth(HttpServletResponse response) throws IOException {
        AuthRequest authRequest = getAuthRequest();
        response.sendRedirect(authRequest.authorize(me.zhyd.oauth.utils.AuthStateUtils.createState()));
    }

    /**
     * 第三方平台授权后的回调地址
     * 由 GitHub 重定向回来，携带 code 等参数
     */
    @GetMapping("/callback/github")
    public void login(AuthCallback callback, HttpServletResponse response) throws IOException {
        AuthRequest authRequest = getAuthRequest();
        AuthResponse<?> authResponse = authRequest.login(callback);
        
        if (authResponse.ok()) {
            AuthUser authUser = (AuthUser) authResponse.getData();
            
            // ==========================================
            // TODO: 在这里编写你的业务逻辑
            // 1. 根据 authUser.getUuid() 或者 authUser.getEmail() 去数据库查询用户是否存在
            // 2. 如果不存在，自动注册该用户并落库
            // 3. 拿到最终的系统内部 UserID
            // ==========================================
            
            // 模拟获取到了用户ID为 10001
            long systemUserId = 10001L; 
            
            // 使用 Sa-Token 登录
            StpUtil.login(systemUserId);
            
            // 登录成功后，重定向回前端页面，并将 Token 放在 URL 参数中带给前端
            // 前端页面可以在加载时读取 URL 参数中的 token 并存入 localStorage
            String tokenValue = StpUtil.getTokenValue();
            String redirectUrl = frontEndUrl + "/?token=" + tokenValue;
            response.sendRedirect(redirectUrl);
        } else {
            // 登录失败，重定向回前端并带上错误信息
            response.sendRedirect(frontEndUrl + "/login?error=oauth_failed");
        }
    }
}