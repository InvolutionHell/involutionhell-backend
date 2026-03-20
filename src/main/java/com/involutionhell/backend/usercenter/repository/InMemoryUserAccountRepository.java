package com.involutionhell.backend.usercenter.repository;

import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.service.PasswordService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryUserAccountRepository {

    private final Map<Long, UserAccount> usersById = new ConcurrentHashMap<>();
    private final Map<String, Long> userIdsByUsername = new ConcurrentHashMap<>();

    /**
     * 初始化内存用户仓库并写入默认种子账号。
     */
    public InMemoryUserAccountRepository(PasswordService passwordService) {
        save(seedUser(
                1L,
                "admin",
                "Admin@123456",
                "系统管理员",
                Set.of("admin", "user-center-manager"),
                Set.of("user:profile:read", "user:center:read", "user:center:manage"),
                passwordService
        ));
        save(seedUser(
                2L,
                "alice",
                "Alice@123456",
                "普通用户",
                Set.of("user"),
                Set.of("user:profile:read"),
                passwordService
        ));
        save(seedUser(
                3L,
                "auditor",
                "Audit@123456",
                "审计员",
                Set.of("auditor"),
                Set.of("user:profile:read", "user:center:read"),
                passwordService
        ));
    }

    /**
     * 根据用户 ID 查询用户。
     */
    public Optional<UserAccount> findById(Long userId) {
        return Optional.ofNullable(usersById.get(userId));
    }

    /**
     * 根据用户名查询用户。
     */
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        Long userId = userIdsByUsername.get(username.trim().toLowerCase());
        return userId == null ? Optional.empty() : findById(userId);
    }

    /**
     * 返回当前仓库中的全部用户，并按 ID 排序。
     */
    public List<UserAccount> findAll() {
        return usersById.values().stream()
                .sorted(Comparator.comparing(UserAccount::id))
                .toList();
    }

    /**
     * 更新指定用户的角色和权限集合。
     */
    public UserAccount updateAuthorization(Long userId, Set<String> roles, Set<String> permissions) {
        UserAccount updatedAccount = usersById.computeIfPresent(
                userId,
                (ignored, existing) -> existing.withAuthorization(roles, permissions)
        );
        if (updatedAccount == null) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        return updatedAccount;
    }

    /**
     * 将用户保存到按 ID 和按用户名索引的内存结构中。
     */
    private void save(UserAccount userAccount) {
        usersById.put(userAccount.id(), userAccount);
        userIdsByUsername.put(userAccount.username().toLowerCase(), userAccount.id());
    }

    /**
     * 创建一条带有加密密码的种子用户记录。
     */
    private UserAccount seedUser(
            Long id,
            String username,
            String rawPassword,
            String displayName,
            Set<String> roles,
            Set<String> permissions,
            PasswordService passwordService
    ) {
        return new UserAccount(
                id,
                username,
                passwordService.hash(rawPassword),
                displayName,
                true,
                roles,
                permissions
        );
    }
}
