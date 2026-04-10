package com.involutionhell.backend.common.config;

import cn.dev33.satoken.stp.StpInterface;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限与角色加载实现。
 * 根据登录用户 ID 从数据库加载其权限集合与角色集合，
 * 供 {@code @SaCheckPermission} / {@code @SaCheckRole} 等注解使用。
 */
@Component
public class SaTokenPermissionImpl implements StpInterface {

    private final UserAccountRepository userAccountRepository;

    public SaTokenPermissionImpl(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return userAccountRepository.findById(Long.valueOf(loginId.toString()))
                .map(account -> List.copyOf(account.permissions()))
                .orElse(List.of());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return userAccountRepository.findById(Long.valueOf(loginId.toString()))
                .map(account -> List.copyOf(account.roles()))
                .orElse(List.of());
    }
}
