package com.involutionhell.backend.events.dto;

import com.involutionhell.backend.usercenter.model.UserAccount;

import java.util.List;

/**
 * 超管用户管理列表项。
 *
 * 独立于现有的 UserView：这里加了 roles 完整快照（前端 checkbox 显隐需要），
 * 不含 passwordHash 等敏感字段。
 *
 * 放在 events 模块的 dto 包是因为"管理员界面"入口目前由 Events 模块承担；
 * 之后如果拆出独立的 admin 模块，再连同 AdminUserController 一起搬过去。
 */
public record AdminUserView(
        Long id,
        String username,
        String displayName,
        String email,
        String avatarUrl,
        Long githubId,
        boolean enabled,
        List<String> roles
) {
    public static AdminUserView from(UserAccount u) {
        return new AdminUserView(
                u.id(),
                u.username(),
                u.displayName(),
                u.email(),
                u.avatarUrl(),
                u.githubId(),
                u.enabled(),
                List.copyOf(u.roles())
        );
    }
}
