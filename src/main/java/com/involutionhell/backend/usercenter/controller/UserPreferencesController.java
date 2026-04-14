package com.involutionhell.backend.usercenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户偏好读写接口，偏好以 JSONB 顶层合并方式存储，前端可自由扩展 key。
 */
@RestController
@RequestMapping("/api/user-center")
public class UserPreferencesController {

    private final UserCenterService userCenterService;

    public UserPreferencesController(UserCenterService userCenterService) {
        this.userCenterService = userCenterService;
    }

    /**
     * 获取当前登录用户的偏好，未设置时返回空对象。
     */
    @SaCheckLogin
    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> getPreferences() {
        long userId = StpUtil.getLoginIdAsLong();
        return ApiResponse.ok(userCenterService.getPreferences(userId));
    }

    /**
     * 合并更新当前登录用户的偏好，body 中的 key 覆盖已有同名 key，其余 key 保留。
     */
    @SaCheckLogin
    @PatchMapping("/preferences")
    public ApiResponse<Map<String, Object>> patchPreferences(@RequestBody Map<String, Object> patch) {
        long userId = StpUtil.getLoginIdAsLong();
        return ApiResponse.ok(userCenterService.patchPreferences(userId, patch));
    }
}
