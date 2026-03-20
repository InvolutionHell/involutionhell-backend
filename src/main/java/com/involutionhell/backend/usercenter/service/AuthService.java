package com.involutionhell.backend.usercenter.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserCenterService userCenterService;
    private final PasswordService passwordService;

    /**
     * 创建认证服务并注入用户与密码服务。
     */
    public AuthService(UserCenterService userCenterService, PasswordService passwordService) {
        this.userCenterService = userCenterService;
        this.passwordService = passwordService;
    }

    /**
     * 校验登录请求。当前阶段仅演示，实际建议通过 OAuth2 流程。
     */
    public LoginResponse login(LoginRequest request) {
        UserAccount userAccount = userCenterService.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!userAccount.enabled()) {
            throw new IllegalStateException("账号已被禁用");
        }
        if (!passwordService.matches(request.password(), userAccount.passwordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 迁移标记：原 Sa-Token 登录已移除，此处应生成基于 JWT 的 Token 或交由 OAuth2 控制。
        return new LoginResponse("Bearer", "MOCK_TOKEN_" + userAccount.id(), UserView.from(userAccount));
    }

    /**
     * 退出当前登录会话。
     */
    public void logout() {
        // 迁移标记：Spring Security 的退出通常通过 SecurityContextLogoutHandler 控制。
    }

    /**
     * 返回当前登录用户视图。
     */
    public UserView currentUser() {
        return userCenterService.currentUser();
    }
}
