package com.involutionhell.backend.usercenter.model;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record UserAccount(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        boolean enabled,
        Set<String> roles,
        Set<String> permissions,
        String avatarUrl,   // GitHub 头像 URL
        String email,       // GitHub 邮箱（可为 null，GitHub 用户可设为私密）
        Long githubId       // GitHub 数字 ID，用于 doc_contributors 贡献者追踪
) {

    /**
     * 创建用户对象时统一规范化角色与权限集合。
     */
    public UserAccount {
        roles = normalizeSet(roles);
        permissions = normalizeSet(permissions);
    }

    /**
     * 基于当前用户信息生成一个新的授权快照。
     */
    public UserAccount withAuthorization(Set<String> newRoles, Set<String> newPermissions) {
        return new UserAccount(id, username, passwordHash, displayName, enabled, newRoles, newPermissions, avatarUrl, email, githubId);
    }

    /**
     * 规范化角色或权限集合，保证去重、去空白且统一小写。
     */
    private static Set<String> normalizeSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalizedValues = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                normalizedValues.add(normalized);
            }
        }
        return Set.copyOf(normalizedValues);
    }
}
