package com.involutionhell.backend.usercenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.service.AuthService;
import com.involutionhell.backend.usercenter.service.RegistrationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")  // context-path 已含 /api/v1，此处不再重复加 /api 前缀
public class AuthController {

    private final AuthService authService;
    private final RegistrationService registrationService;

    public AuthController(AuthService authService, RegistrationService registrationService) {
        this.authService = authService;
        this.registrationService = registrationService;
    }

    record OtpRequest(String email, String code) {}

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
    @SaCheckLogin
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.okMessage("退出成功");
    }

    /**
     * 查询当前登录用户信息。
     */
    @SaCheckLogin
    @GetMapping("/me")
    public ApiResponse<UserView> currentUser() {
        return ApiResponse.ok(authService.currentUser());
    }

    // ---------- 新用户注册：第三方登录后验证邮箱（pendingId 只认 httpOnly cookie） ----------
    // 状态码即错误码，前端按它选文案：400 邮箱格式/验证码不对，409 邮箱已属于已有账号，
    // 410 注册会话没了（过期/锁死/已用）→ 重新登录，429 发太多，503 发信失败可重试。

    /** 验证页预填：provider、展示名、头像、provider 给的邮箱（都是用户自己刚授权的资料）。 */
    @GetMapping("/register/pending")
    public ResponseEntity<ApiResponse<?>> pending(
            @CookieValue(name = OAuthController.REG_COOKIE, defaultValue = "") String pendingId) {
        return registrationService.getPending(pendingId)
                .<ResponseEntity<ApiResponse<?>>>map(p -> ResponseEntity.ok(ApiResponse.ok(p)))
                .orElseGet(() -> fail(HttpStatus.GONE, "注册会话已失效，请重新登录"));
    }

    @PostMapping("/register/send-otp")
    public ResponseEntity<ApiResponse<?>> sendOtp(
            @CookieValue(name = OAuthController.REG_COOKIE, defaultValue = "") String pendingId,
            @RequestBody OtpRequest req) {
        return switch (registrationService.sendOtp(pendingId, req.email())) {
            case SENT -> ResponseEntity.ok(ApiResponse.okMessage("验证码已发送"));
            case INVALID_EMAIL -> fail(HttpStatus.BAD_REQUEST, "邮箱格式不正确");
            case RATE_LIMITED -> fail(HttpStatus.TOO_MANY_REQUESTS, "发送太频繁，请稍后再试");
            case SESSION_EXPIRED -> fail(HttpStatus.GONE, "注册会话已失效，请重新登录");
            case SEND_FAILED -> fail(HttpStatus.SERVICE_UNAVAILABLE, "验证码发送失败，请稍后重试");
        };
    }

    @PostMapping("/register/verify")
    public ResponseEntity<ApiResponse<?>> verify(
            @CookieValue(name = OAuthController.REG_COOKIE, defaultValue = "") String pendingId,
            @RequestBody OtpRequest req) {
        var verified = registrationService.verifyAndConsume(pendingId, req.email(), req.code());
        if (verified.isEmpty()) {
            return registrationService.getPending(pendingId).isPresent()
                    ? fail(HttpStatus.BAD_REQUEST, "验证码错误或已过期")
                    : fail(HttpStatus.GONE, "注册会话已失效，请重新登录");
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok("注册成功", authService.completeRegistration(verified.get())));
        } catch (AuthService.EmailTakenException e) {
            return fail(HttpStatus.CONFLICT, "该邮箱已属于一个已有账号：请用那个账号登录，再到设置里连接这个登录方式");
        } catch (DataIntegrityViolationException e) {
            // 同一第三方身份被并发注册/绑定了：重新登录会按身份直接登进去
            return fail(HttpStatus.GONE, "注册会话已失效，请重新登录");
        }
    }

    private static ResponseEntity<ApiResponse<?>> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.fail(message));
    }
}
