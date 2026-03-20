package com.involutionhell.backend.usercenter.service;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import com.involutionhell.backend.usercenter.dto.UserAuthorizationUpdateRequest;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

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
     * 获取当前登录用户的信息。
     */
    public UserView currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long currentUserId;
        
        if (principal instanceof Jwt jwt) {
            currentUserId = Long.parseLong(jwt.getSubject());
        } else if (principal instanceof Long id) {
            currentUserId = id;
        } else {
            // 这里可以扩展更多的主体解析逻辑
            throw new IllegalStateException("无法解析当前用户身份");
        }
        
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
