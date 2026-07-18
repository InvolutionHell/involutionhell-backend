package com.involutionhell.backend.usercenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import com.involutionhell.backend.usercenter.model.UserIdentity;
import com.involutionhell.backend.usercenter.repository.UserIdentityRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * user_identities 表与仓库的行为契约：
 * 两条 UNIQUE 约束、FK 级联删除、启动回填幂等性。
 * 约束语义的 why 见 docs/wiki/adr/001-multi-provider-identity.md。
 */
class UserIdentityRepositoryTests extends AbstractWebIntegrationTest {

    @Autowired
    private UserIdentityRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    // H2 测试上下文共享同一内存库（DB_CLOSE_DELAY=-1），本类插入的 user_accounts 行
    // 若不清理会泄漏进别的测试（如 JdbcUserAccountRepositoryTests 断言只有 4 个种子用户）。
    // FK ON DELETE CASCADE 会连带删掉这些账号的 user_identities。
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM user_accounts WHERE username LIKE 'identity-test-%'");
    }

    /** 建一个最小可用的 user_accounts 行，返回 id。 */
    private long createUser(Long githubId) {
        String username = "identity-test-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_accounts (username, password_hash, enabled, roles, permissions, github_id) "
                        + "VALUES (?, '!', TRUE, 'user', '', ?)",
                username, githubId);
        return jdbc.queryForObject("SELECT id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private UserIdentity identity(long userId, String provider, String providerUserId) {
        return new UserIdentity(null, userId, provider, providerUserId, null, null, null, null);
    }

    @Test
    void insertAndFindRoundtrip() {
        long userId = createUser(null);
        String puid = UUID.randomUUID().toString();

        UserIdentity saved = repository.insert(identity(userId, "discord", puid));
        assertThat(saved.id()).isNotNull();
        assertThat(saved.linkedAt()).isNotNull();
        assertThat(saved.lastLoginAt()).isNull();

        assertThat(repository.findByProviderAndProviderUserId("discord", puid))
                .hasValueSatisfying(found -> assertThat(found.userId()).isEqualTo(userId));
        assertThat(repository.findByUserId(userId)).hasSize(1);

        repository.touchLastLogin(saved.id());
        assertThat(repository.findByProviderAndProviderUserId("discord", puid))
                .hasValueSatisfying(found -> assertThat(found.lastLoginAt()).isNotNull());
    }

    @Test
    void sameProviderIdentityCannotBindTwoAccounts() {
        long first = createUser(null);
        long second = createUser(null);
        String puid = UUID.randomUUID().toString();

        repository.insert(identity(first, "github", puid));
        assertThatThrownBy(() -> repository.insert(identity(second, "github", puid)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void oneAccountCannotBindSameProviderTwice() {
        long userId = createUser(null);

        repository.insert(identity(userId, "google", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> repository.insert(identity(userId, "google", UUID.randomUUID().toString())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deletingAccountCascadesIdentities() {
        long userId = createUser(null);
        repository.insert(identity(userId, "discord", UUID.randomUUID().toString()));

        jdbc.update("DELETE FROM user_accounts WHERE id = ?", userId);

        assertThat(repository.findByUserId(userId)).isEmpty();
    }

    /**
     * 生产 schema.sql 的回填语句每次启动都会执行，必须幂等——
     * 这里在 H2 上原样执行两遍，验证第二遍既不报错也不产生重复行。
     */
    @Test
    void githubBackfillIsIdempotent() {
        long githubId = 900_000_000L + (long) (Math.random() * 1_000_000);
        long userId = createUser(githubId);

        String backfill = "INSERT INTO user_identities (user_id, provider, provider_user_id) "
                + "SELECT id, 'github', CAST(github_id AS VARCHAR) FROM user_accounts "
                + "WHERE github_id IS NOT NULL ON CONFLICT DO NOTHING";
        jdbc.execute(backfill);
        jdbc.execute(backfill);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class, userId);
        assertThat(rows).isEqualTo(1);
    }
}
