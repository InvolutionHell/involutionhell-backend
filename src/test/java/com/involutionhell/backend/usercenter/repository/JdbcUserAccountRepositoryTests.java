package com.involutionhell.backend.usercenter.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.involutionhell.backend.usercenter.model.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * JdbcUserAccountRepository 集成测试。
 * 使用 H2 内存库（PostgreSQL MODE），种子数据：admin(id=1)、alice(id=2)、auditor(id=3)。
 * 每个 @Test 都在事务中执行并自动回滚，保证测试间互不干扰。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:backend;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
@ActiveProfiles("test")
@Transactional
class JdbcUserAccountRepositoryTests {

    @Autowired
    private JdbcUserAccountRepository repository;

    // =============================================
    // findById
    // =============================================

    @Test
    void findByIdReturnsUserWhenExists() {
        Optional<UserAccount> result = repository.findById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().username()).isEqualTo("admin");
        assertThat(result.get().enabled()).isTrue();
        assertThat(result.get().roles()).contains("admin");
    }

    @Test
    void findByIdReturnsEmptyWhenUserMissing() {
        Optional<UserAccount> result = repository.findById(999L);

        assertThat(result).isEmpty();
    }

    // =============================================
    // findByUsername
    // =============================================

    @Test
    void findByUsernameReturnsUserWhenExists() {
        Optional<UserAccount> result = repository.findByUsername("alice");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(2L);
        assertThat(result.get().displayName()).isEqualTo("Alice");
        // alice 没有 github_id，应为 null
        assertThat(result.get().githubId()).isNull();
    }

    @Test
    void findByUsernameReturnsEmptyWhenUserMissing() {
        Optional<UserAccount> result = repository.findByUsername("nobody");

        assertThat(result).isEmpty();
    }

    // =============================================
    // findAll
    // =============================================

    @Test
    void findAllReturnsSeedUsersOrderedById() {
        List<UserAccount> all = repository.findAll();

        // 种子数据：admin、alice、auditor
        assertThat(all).hasSize(3);
        assertThat(all.get(0).username()).isEqualTo("admin");
        assertThat(all.get(1).username()).isEqualTo("alice");
        assertThat(all.get(2).username()).isEqualTo("auditor");
    }

    // =============================================
    // insert
    // =============================================

    @Test
    void insertCreatesUserAndReturnsWithGeneratedId() {
        UserAccount toInsert = new UserAccount(
                null, "newuser", "hash-value", "新用户",
                true, Set.of("user"), Set.of("user:profile:read"),
                "https://avatar.example.com", "newuser@example.com", 99999L
        );

        UserAccount saved = repository.insert(toInsert);

        assertThat(saved.id()).isNotNull().isPositive();
        assertThat(saved.username()).isEqualTo("newuser");
        assertThat(saved.displayName()).isEqualTo("新用户");
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.roles()).containsExactly("user");
        assertThat(saved.permissions()).containsExactly("user:profile:read");
        assertThat(saved.avatarUrl()).isEqualTo("https://avatar.example.com");
        assertThat(saved.email()).isEqualTo("newuser@example.com");
        assertThat(saved.githubId()).isEqualTo(99999L);
    }

    @Test
    void insertHandlesNullGithubIdAndEmail() {
        // GitHub 用户邮箱可能设为私密（null），github_id 也可能无法解析
        UserAccount toInsert = new UserAccount(
                null, "github_user", "random-hash", "GitHub 用户",
                true, Set.of("user"), Set.of(),
                null, null, null
        );

        UserAccount saved = repository.insert(toInsert);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.username()).isEqualTo("github_user");
        assertThat(saved.githubId()).isNull();
        assertThat(saved.email()).isNull();
        assertThat(saved.avatarUrl()).isNull();
    }

    @Test
    void insertPersistsEmptyRolesAsEmptySet() {
        UserAccount toInsert = new UserAccount(
                null, "norole_user", "hash", "无角色用户",
                true, Set.of(), Set.of(), null, null, null
        );

        UserAccount saved = repository.insert(toInsert);

        assertThat(saved.roles()).isEmpty();
        assertThat(saved.permissions()).isEmpty();
    }

    // =============================================
    // updateAuthorization
    // =============================================

    @Test
    void updateAuthorizationChangesRolesAndPermissions() {
        Set<String> newRoles = Set.of("editor", "reviewer");
        Set<String> newPermissions = Set.of("user:profile:read", "user:center:read");

        UserAccount updated = repository.updateAuthorization(2L, newRoles, newPermissions);

        assertThat(updated.username()).isEqualTo("alice");
        assertThat(updated.roles()).containsExactlyInAnyOrder("editor", "reviewer");
        assertThat(updated.permissions()).containsExactlyInAnyOrder("user:profile:read", "user:center:read");
    }

    @Test
    void updateAuthorizationThrowsWhenUserMissing() {
        assertThatThrownBy(() -> repository.updateAuthorization(999L, Set.of("user"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在: 999");
    }

    // =============================================
    // updateProfile
    // =============================================

    @Test
    void updateProfileUpdatesGithubFields() {
        UserAccount updated = repository.updateProfile(
                2L, "Alice Updated", "https://new-avatar.com", "alice@github.com", 12345L
        );

        assertThat(updated.username()).isEqualTo("alice");
        assertThat(updated.displayName()).isEqualTo("Alice Updated");
        assertThat(updated.avatarUrl()).isEqualTo("https://new-avatar.com");
        assertThat(updated.email()).isEqualTo("alice@github.com");
        assertThat(updated.githubId()).isEqualTo(12345L);
    }

    @Test
    void updateProfileHandlesNullEmailAndGithubId() {
        // 邮箱私密、uuid 无法解析 → 允许 null
        UserAccount updated = repository.updateProfile(2L, "Alice", null, null, null);

        assertThat(updated.username()).isEqualTo("alice");
        assertThat(updated.displayName()).isEqualTo("Alice");
        assertThat(updated.avatarUrl()).isNull();
        assertThat(updated.email()).isNull();
        assertThat(updated.githubId()).isNull();
    }

    @Test
    void updateProfileThrowsWhenUserMissing() {
        assertThatThrownBy(() -> repository.updateProfile(999L, "Name", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在: 999");
    }
}
