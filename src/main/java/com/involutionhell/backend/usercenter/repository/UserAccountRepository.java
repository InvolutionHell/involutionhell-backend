package com.involutionhell.backend.usercenter.repository;

import com.involutionhell.backend.usercenter.model.UserAccount;
import java.util.List;
import java.util.Map;
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
     * 按 GitHub 数字 ID 查询用户（对应 user_accounts.github_id）。
     * 用于 profile 页 /u/{githubId} 的公开访问路径。
     */
    Optional<UserAccount> findByGithubId(Long githubId);

    /**
     * 按邮箱（大小写不敏感）查询用户。用于第三方登录的"已验证邮箱自动关联"。
     * 返回 List 而非 Optional：email 无 UNIQUE 约束，调用方需自行判断"恰好一个"才关联，
     * 多个匹配时保守不关联。
     */
    List<UserAccount> findByEmail(String email);

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

    /**
     * 就地更新指定用户的 password_hash。
     * 用于 AuthService 在登录成功后把 legacy SHA-256 哈希迁移到 bcrypt（lazy upgrade）。
     * 不返回值——调用方不需要回读，写完即可继续。
     */
    void updatePasswordHash(Long userId, String passwordHash);

    /**
     * 清空指定用户的 github_id 列。解绑 github 身份时同步调用——否则 schema.sql
     * 的启动回填会在下次重启时按残留的 github_id 把身份静默复活（ADR-001）。
     */
    void clearGithubId(Long userId);

    /**
     * 仅当 github_id 当前为空时写入。用于"先非 github 注册、后 github 登录挂靠"时补列，
     * 不覆盖已有值。撞 UNIQUE(github_id) 时抛异常由调用方按"不阻断登录"处理。
     */
    void setGithubIdIfAbsent(Long userId, Long githubId);

    /**
     * 查询指定用户的偏好 Map，用户不存在时抛 IllegalArgumentException。
     */
    Map<String, Object> findPreferences(Long userId);

    /**
     * 以 patch 为单位在数据库端原子合并用户偏好（顶层 key 覆盖），返回合并后的全量偏好。
     * PostgreSQL 实现走 `preferences || ?::jsonb` 单条 UPDATE，避免并发 lost update；
     * H2 走 read-merge-write 路径兼容测试。
     */
    Map<String, Object> patchPreferences(Long userId, Map<String, Object> patch);
}