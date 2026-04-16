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
     * 公开读取指定用户的主页数据：基本资料 + 偏好。
     * 贡献文档列表不在这里返回，前端直接读 build-time 生成的 site-leaderboard.json
     * 按 githubId 匹配 —— 避免每次访问 /u/{x} 都打 Neon，节省免费额度。
     * docs 本身是 git-based，JSON 新鲜度和 DB 一致（都是 deploy 级）。
     *
     * identifier 约定：
     * - 纯数字 → 按 github_id 查询（推荐的 canonical URL，如 /u/114939201）
     * - 字符串 → 按 username 查询（兼容 "github_&lt;id&gt;" / "alice" / "admin"）
     *
     * 返回结构：
     * {
     *   "user": UserView,           // 基本信息 + githubId + avatarUrl
     *   "preferences": { ... }      // JSONB: bio / tagline / links / projects / pinned_papers
     * }
     */
    @GetMapping("/profile/{identifier}")
    public ApiResponse<Map<String, Object>> getPublicProfile(@PathVariable String identifier) {
        Optional<UserAccount> account = userCenterService.findByIdentifier(identifier);
        if (account.isEmpty()) {
            return new ApiResponse<>(false, "用户不存在: " + identifier, null);
        }
        UserAccount userAccount = account.get();
        Map<String, Object> preferences = userCenterService.getPreferences(userAccount.id());

        Map<String, Object> body = new HashMap<>();
        body.put("user", UserView.from(userAccount));
        body.put("preferences", preferences);
        return ApiResponse.ok(body);
    }
}
