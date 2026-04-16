package com.involutionhell.backend.usercenter.follows;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户关注 / 粉丝相关接口。
 *
 * 公开接口（SaToken 白名单）：
 * - GET  /api/user-center/follows/stats/{identifier}  统计某人的粉丝/关注数
 * - GET  /api/user-center/follows/followers/{identifier}  谁关注了 Ta
 * - GET  /api/user-center/follows/following/{identifier}  Ta 关注了谁
 * - GET  /api/user-center/follows/is-following/{identifier}  当前登录用户是否关注 Ta（匿名返回 false）
 *
 * 登录才能操作的接口（SaCheckLogin）：
 * - POST   /api/user-center/follows/{identifier}
 * - DELETE /api/user-center/follows/{identifier}
 *
 * identifier 参数和 /u/{identifier} 保持一致：纯数字按 github_id 查，否则按 username 查。
 */
@RestController
@RequestMapping("/api/user-center/follows")
public class FollowController {

    private final FollowService followService;
    private final UserCenterService userCenterService;

    public FollowController(FollowService followService, UserCenterService userCenterService) {
        this.followService = followService;
        this.userCenterService = userCenterService;
    }

    /** 关注 (identifier)。幂等。 */
    @SaCheckLogin
    @PostMapping("/{identifier}")
    public ApiResponse<Map<String, Object>> follow(@PathVariable String identifier) {
        long followerId = StpUtil.getLoginIdAsLong();
        UserAccount target = resolveOrThrow(identifier);
        followService.follow(followerId, target.id());
        return okStats(target);
    }

    /** 取消关注 (identifier)。幂等。 */
    @SaCheckLogin
    @DeleteMapping("/{identifier}")
    public ApiResponse<Map<String, Object>> unfollow(@PathVariable String identifier) {
        long followerId = StpUtil.getLoginIdAsLong();
        UserAccount target = resolveOrThrow(identifier);
        followService.unfollow(followerId, target.id());
        return okStats(target);
    }

    /** 统计 (identifier) 的粉丝 / 关注数。匿名可访问。 */
    @GetMapping("/stats/{identifier}")
    public ApiResponse<Map<String, Object>> stats(@PathVariable String identifier) {
        UserAccount target = resolveOrThrow(identifier);
        return okStats(target);
    }

    /** 当前登录用户是否关注了 (identifier)。匿名返回 false。 */
    @GetMapping("/is-following/{identifier}")
    public ApiResponse<Map<String, Object>> isFollowing(@PathVariable String identifier) {
        UserAccount target = resolveOrThrow(identifier);
        boolean yes = false;
        try {
            if (StpUtil.isLogin()) {
                long followerId = StpUtil.getLoginIdAsLong();
                yes = followService.isFollowing(followerId, target.id());
            }
        } catch (Exception ignored) {
            // 未登录或 token 非法都按 false 处理
        }
        Map<String, Object> body = new HashMap<>();
        body.put("isFollowing", yes);
        return ApiResponse.ok(body);
    }

    /** 粉丝列表：谁关注了 (identifier)。返回 UserView 数组。 */
    @GetMapping("/followers/{identifier}")
    public ApiResponse<List<UserView>> followers(
            @PathVariable String identifier,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        UserAccount target = resolveOrThrow(identifier);
        List<Long> ids = followService.listFollowerIds(target.id(), limit, offset);
        return ApiResponse.ok(usersByIds(ids));
    }

    /** 关注列表：(identifier) 关注了谁。返回 UserView 数组。 */
    @GetMapping("/following/{identifier}")
    public ApiResponse<List<UserView>> following(
            @PathVariable String identifier,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        UserAccount target = resolveOrThrow(identifier);
        List<Long> ids = followService.listFollowingIds(target.id(), limit, offset);
        return ApiResponse.ok(usersByIds(ids));
    }

    // ----- helpers -----

    private UserAccount resolveOrThrow(String identifier) {
        Optional<UserAccount> account = userCenterService.findByIdentifier(identifier);
        if (account.isEmpty()) {
            throw new IllegalArgumentException("用户不存在: " + identifier);
        }
        return account.get();
    }

    /**
     * 给定用户的粉丝/关注数标准返回体。
     * 登录用户再附带一条 `isFollowing` 方便前端一次拉到位。
     */
    private ApiResponse<Map<String, Object>> okStats(UserAccount target) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", target.id());
        body.put("followerCount", followService.countFollowers(target.id()));
        body.put("followingCount", followService.countFollowing(target.id()));
        try {
            if (StpUtil.isLogin()) {
                long me = StpUtil.getLoginIdAsLong();
                body.put("isFollowing",
                        me != target.id() && followService.isFollowing(me, target.id()));
            } else {
                body.put("isFollowing", false);
            }
        } catch (Exception ignored) {
            body.put("isFollowing", false);
        }
        return ApiResponse.ok(body);
    }

    /**
     * 批量把 user_accounts.id 展开成 UserView。保留输入顺序（关注时间倒序）。
     */
    private List<UserView> usersByIds(List<Long> ids) {
        return ids.stream()
                .map(userCenterService::getUser) // 不存在直接抛；这里我们默认 DB 一致
                .toList();
    }
}
