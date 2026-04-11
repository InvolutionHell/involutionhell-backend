package com.involutionhell.backend.usercenter.repository;

import com.involutionhell.backend.usercenter.model.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户账号数据访问接口（Java 侧 Sa-Token 认证用户，对应 user_accounts 表）。
 */
public interface UserAccountRepository {

    /**
     * 按主键查询用户。
     */
    Optional<UserAccount> findById(Long id);

    /**
     * 按用户名查询用户。
     */
    Optional<UserAccount> findByUsername(String username);

    /**
     * 查询所有用户。
     */
    List<UserAccount> findAll();

    /**
     * 更新指定用户的角色与权限，返回更新后的用户对象。
     */
    UserAccount updateAuthorization(Long userId, Set<String> roles, Set<String> permissions);

    /**
     * 新增用户，并返回插入后的用户对象（包含生成的自增 ID）。
     */
    UserAccount insert(UserAccount userAccount);

    /**
     * 更新 GitHub 用户的个人资料（展示名、头像、邮箱、GitHub ID），每次登录时刷新。
     */
    UserAccount updateProfile(Long userId, String displayName, String avatarUrl, String email, Long githubId);
}