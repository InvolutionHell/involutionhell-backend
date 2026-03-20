package com.involutionhell.backend.usercenter.security;

import cn.dev33.satoken.stp.StpInterface;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.InMemoryUserAccountRepository;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UserStpInterface implements StpInterface {

    private final InMemoryUserAccountRepository userAccountRepository;

    /**
     * 创建 Sa-Token 权限桥接组件。
     */
    public UserStpInterface(InMemoryUserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * 返回当前登录账号拥有的权限列表。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return findUser(loginId).permissions().stream().toList();
    }

    /**
     * 返回当前登录账号拥有的角色列表。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return findUser(loginId).roles().stream().toList();
    }

    /**
     * 将 Sa-Token 传入的登录标识解析为用户对象。
     */
    private UserAccount findUser(Object loginId) {
        Long userId = Long.parseLong(String.valueOf(loginId));
        return userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("登录用户不存在: " + userId));
    }
}
