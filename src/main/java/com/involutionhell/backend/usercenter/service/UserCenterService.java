package com.involutionhell.backend.usercenter.service;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.usercenter.dto.UserAuthorizationUpdateRequest;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class UserCenterService {

    /**
     * 不允许通过 API 授予的角色黑名单。
     *
     * superadmin：管理其他 admin 的上位角色。必须走 DB 直接 UPDATE 授予，
     * 防止 admin 通过本接口整集替换 roles 绕过 AdminUserController#setAdminRole 的 superadmin 保护。
     * 未来如果还有"系统级"角色（system / bridge 等）需要锁定，加在这里即可。
     *
     * 安全不变量 INV-001 见 SECURITY.md / SecurityInvariantsTests。
     */
    private static final Set<String> RESTRICTED_ROLES = Set.of("superadmin");

    private final UserAccountRepository userAccountRepository;

    /**
     * 创建用户中心服务并注入用户仓库。
     */
    public UserCenterService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * 按用户名查询用户领域对象。
     */
    public Optional<UserAccount> findByUsername(String username) {
        return userAccountRepository.findByUsername(username);
    }

    /**
     * 按标识符查找用户：纯数字优先走 github_id（/u/114939201 形式），
     * 否则回退到 username 查找（兼容 "github_&lt;id&gt;" / "alice" / "admin" 等所有系统用户名）。
     * 用于 profile 页 /u/{identifier} 的匿名访问。
     */
    public Optional<UserAccount> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        if (identifier.chars().allMatch(Character::isDigit)) {
            try {
                long gid = Long.parseLong(identifier);
                Optional<UserAccount> byGid = userAccountRepository.findByGithubId(gid);
                if (byGid.isPresent()) return byGid;
            } catch (NumberFormatException ignored) {
                // 数字太大无法 parse 时继续 fallthrough 走 username
            }
        }
        return userAccountRepository.findByUsername(identifier);
    }

    /**
     * 新增用户。
     */
    public UserAccount createUser(UserAccount userAccount) {
        return userAccountRepository.insert(userAccount);
    }

    /**
     * 刷新 GitHub 用户的个人资料（展示名、头像、邮箱、GitHub ID），每次登录时调用。
     */
    public UserAccount updateProfile(Long userId, String displayName, String avatarUrl, String email, Long githubId) {
        return userAccountRepository.updateProfile(userId, displayName, avatarUrl, email, githubId);
    }

    /**
     * 获取当前登录用户的信息。
     */
    public UserView currentUser() {
        long currentUserId = StpUtil.getLoginIdAsLong();
        return getUser(currentUserId);
    }

    /**
     * 查询用户中心中的全部用户视图。
     */
    public List<UserView> listUsers() {
        return userAccountRepository.findAll().stream()
                .map(UserView::from)
                .toList();
    }

    /**
     * 根据用户 ID 查询用户视图。
     */
    public UserView getUser(Long userId) {
        return userAccountRepository.findById(userId)
                .map(UserView::from)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
    }

    /**
     * 更新指定用户的角色与权限并返回最新视图。
     *
     * 安全不变量 INV-001：本接口不允许授予 RESTRICTED_ROLES 中的任何角色。
     * 攻击场景：admin 自带 user:center:manage 权限可调本接口；
     * 若不拦截，admin 可以整集替换 roles 自行提权为 superadmin，
     * 绕过 AdminUserController#setAdminRole 的 superadmin 保护边界。
     * 见 SecurityInvariantsTests#admin不能通过PUT_users_authorization给自己加superadmin角色。
     */
    public UserView updateAuthorization(Long userId, UserAuthorizationUpdateRequest request) {
        Set<String> requestedRoles = request.roles() == null ? Set.of() : request.roles();
        for (String role : requestedRoles) {
            if (RESTRICTED_ROLES.contains(role)) {
                throw new IllegalArgumentException("不允许通过本接口授予角色: " + role);
            }
        }

        UserAccount updatedAccount = userAccountRepository.updateAuthorization(
                userId,
                requestedRoles,
                request.permissions()
        );
        return UserView.from(updatedAccount);
    }

    /**
     * 获取指定用户的偏好 Map，未设置时返回空 Map。
     */
    public Map<String, Object> getPreferences(Long userId) {
        return userAccountRepository.findPreferences(userId);
    }

    /**
     * 将 patch 合并进用户偏好（顶层 key 覆盖），返回更新后全量偏好。
     * 合并原子性由 repository 层保证（PostgreSQL 用 jsonb 原生 `||` 单条 UPDATE，避免并发 lost update）。
     */
    public Map<String, Object> patchPreferences(Long userId, Map<String, Object> patch) {
        return userAccountRepository.patchPreferences(userId, patch);
    }
}