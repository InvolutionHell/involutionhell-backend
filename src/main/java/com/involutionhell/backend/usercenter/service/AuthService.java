package com.involutionhell.backend.usercenter.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
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
     * 校验登录请求并创建新的登录会话。
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

        StpUtil.login(userAccount.id());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();
        return new LoginResponse(tokenInfo.getTokenName(), tokenInfo.getTokenValue(), UserView.from(userAccount));
    }

    /**
     * 退出当前登录会话。
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 返回当前登录用户视图。
     */
    public UserView currentUser() {
        return userCenterService.currentUser();
    }
}
