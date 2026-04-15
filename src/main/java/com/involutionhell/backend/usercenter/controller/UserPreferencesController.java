package com.involutionhell.backend.usercenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

    /**
     * 公开读取指定用户的主页数据：基本资料 + 偏好（bio / tagline / links / projects / pinned_papers）。
     * 用于 /u/[username] 个人主页 SSR，任何人可访问，不需要登录。
     *
     * 返回结构：
     * {
     *   "user": UserView,         // 不含角色/权限敏感字段？当前 UserView 有 roles/permissions，
     *                             // 若未来限制展示在 DTO 层过滤；本期先原样返回避免 scope 扩大
     *   "preferences": { ... }    // 原始 JSONB
     * }
     */
    @GetMapping("/profile/{username}")
    public ApiResponse<Map<String, Object>> getPublicProfile(@PathVariable String username) {
        Optional<UserAccount> account = userCenterService.findByUsername(username);
        if (account.isEmpty()) {
            // ApiResponse.fail 泛型是 Void，这里显式构造匹配 Map 泛型的失败响应
            return new ApiResponse<>(false, "用户不存在: " + username, null);
        }
        UserAccount userAccount = account.get();
        Map<String, Object> preferences = userCenterService.getPreferences(userAccount.id());

        Map<String, Object> body = new HashMap<>();
        body.put("user", UserView.from(userAccount));
        body.put("preferences", preferences);
        return ApiResponse.ok(body);
    }
}
