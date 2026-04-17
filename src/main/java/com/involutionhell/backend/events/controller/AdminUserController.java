package com.involutionhell.backend.events.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.events.dto.AdminUserView;
import com.involutionhell.backend.events.dto.UpdateUserAdminRoleRequest;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 超管用户管理接口。@SaCheckRole("superadmin") 类级保护。
 *
 * 路由：
 * - GET /api/admin/users?q=xxx        列出全部用户（可按 username / display_name 模糊搜索）
 * - PUT /api/admin/users/{id}/admin   {admin: true|false} 授予 / 撤销 admin 角色
 *
 * 为什么只有这两个接口：
 *   - 产品目前只需要"让超管给其他人挂 / 摘 admin"，不需要编辑邮箱 / 头像等资料
 *   - superadmin 角色不允许通过 API 授予；想加第二个 superadmin 只能走 DB，防止误操作
 *   - user 角色由 AuthService OAuth 流程自动挂，也不用 API 管
 *
 * 路径前缀 /api/admin/* 和 EventAdminController 保持同一家族，SaToken 白名单默认
 * 不放行，走登录 + 角色校验兜底。
 */
@RestController
@RequestMapping("/api/admin/users")
@SaCheckRole("superadmin")
public class AdminUserController {

    /** 允许由 API 授予的角色白名单。superadmin 不在此列——必须走 DB，防误操作。 */
    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_USER = "user";
    private static final String ROLE_SUPERADMIN = "superadmin";

    private final UserAccountRepository userAccountRepository;

    public AdminUserController(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @GetMapping
    public ApiResponse<List<AdminUserView>> list(@RequestParam(required = false) String q) {
        List<UserAccount> all = userAccountRepository.findAll();
        String keyword = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
        List<AdminUserView> views = all.stream()
                .filter(u -> matches(u, keyword))
                .map(AdminUserView::from)
                .toList();
        return ApiResponse.ok(views);
    }

    private static boolean matches(UserAccount u, String kw) {
        if (kw == null || kw.isEmpty()) return true;
        if (u.username() != null
                && u.username().toLowerCase(Locale.ROOT).contains(kw)) {
            return true;
        }
        if (u.displayName() != null
                && u.displayName().toLowerCase(Locale.ROOT).contains(kw)) {
            return true;
        }
        if (u.email() != null
                && u.email().toLowerCase(Locale.ROOT).contains(kw)) {
            return true;
        }
        return false;
    }

    /**
     * 切换某用户 admin 角色。
     *
     * 规则：
     *   - 目标用户是 superadmin 时直接 403 —— 不允许给 superadmin 再 "去 admin 化"
     *     （superadmin 本来就包含 admin 语义，降级 superadmin 只能走 DB）
     *   - 自己不能给自己摘 admin（防止唯一 admin 把自己锁出来）
     *   - user 角色始终保留；superadmin 角色保留不动
     *   - permissions 字段我们在这一阶段还不细管，直接保留原值；未来如果要按 role
     *     派发权限，这里再扩
     */
    @PutMapping("/{userId}/admin")
    public ApiResponse<AdminUserView> setAdminRole(
            @PathVariable Long userId,
            @RequestBody UpdateUserAdminRoleRequest req) {
        if (req == null) return new ApiResponse<>(false, "请求体不能为空", null);

        Optional<UserAccount> maybe = userAccountRepository.findById(userId);
        if (maybe.isEmpty()) return new ApiResponse<>(false, "用户不存在", null);
        UserAccount target = maybe.get();

        if (target.roles().contains(ROLE_SUPERADMIN)) {
            return new ApiResponse<>(false, "superadmin 用户不允许通过 API 修改角色", null);
        }

        long self = StpUtil.getLoginIdAsLong();
        if (target.id().equals(self) && !req.admin()) {
            return new ApiResponse<>(false, "不能给自己撤销 admin 角色", null);
        }

        // 维持原 roles 集合，移除 ROLE_ADMIN 后按请求再加回去；user 始终保留
        Set<String> next = new LinkedHashSet<>(target.roles());
        next.add(ROLE_USER);
        if (req.admin()) {
            next.add(ROLE_ADMIN);
        } else {
            next.remove(ROLE_ADMIN);
        }

        UserAccount updated = userAccountRepository.updateAuthorization(
                target.id(), next, target.permissions());
        return ApiResponse.ok("角色已更新", AdminUserView.from(updated));
    }
}
