package com.involutionhell.backend.usercenter.service;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.usercenter.dto.UserAuthorizationUpdateRequest;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserCenterService {

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
     */
    public UserView updateAuthorization(Long userId, UserAuthorizationUpdateRequest request) {
        UserAccount updatedAccount = userAccountRepository.updateAuthorization(
                userId,
                request.roles(),
                request.permissions()
        );
        return UserView.from(updatedAccount);
    }
}