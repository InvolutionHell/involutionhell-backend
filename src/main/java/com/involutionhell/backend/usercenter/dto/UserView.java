package com.involutionhell.backend.usercenter.dto;

import com.involutionhell.backend.usercenter.model.UserAccount;
import java.util.Set;

public record UserView(
        Long id,
        String username,
        String displayName,
        boolean enabled,
        Set<String> roles,
        Set<String> permissions,
        String avatarUrl,  // GitHub 头像 URL，前端 UserMenu 显示头像用
        String email,      // GitHub 邮箱（可为 null）
        Long githubId      // GitHub 数字 ID，用于贡献者追踪
) {

    /**
     * 将用户领域对象转换为对外返回的视图对象。
     */
    public static UserView from(UserAccount userAccount) {
        return new UserView(
                userAccount.id(),
                userAccount.username(),
                userAccount.displayName(),
                userAccount.enabled(),
                userAccount.roles(),
                userAccount.permissions(),
                userAccount.avatarUrl(),
                userAccount.email(),
                userAccount.githubId()
        );
    }
}
