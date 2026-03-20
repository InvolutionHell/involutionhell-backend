package com.involutionhell.backend.usercenter.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * 创建认证控制器并注入认证服务。
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 校验用户名密码并返回登录后的 token 信息。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok("登录成功", authService.login(request));
    }

    /**
     * 退出当前登录会话。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.okMessage("退出成功");
    }

    /**
     * 查询当前登录用户信息。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ApiResponse<UserView> currentUser() {
        return ApiResponse.ok(authService.currentUser());
    }
}
