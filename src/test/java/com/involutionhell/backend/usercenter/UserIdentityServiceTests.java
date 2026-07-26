package com.involutionhell.backend.usercenter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import com.involutionhell.backend.usercenter.service.UserIdentityService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 身份查看 / 解绑（M2a）的行为契约：解绑最后一种身份被挡（防锁死）、
 * 解绑 github 同步清 github_id 列（防启动回填静默复活）、鉴权门。
 */
class UserIdentityServiceTests extends AbstractWebIntegrationTest {

    @Autowired
    private UserIdentityService service;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM user_accounts WHERE username LIKE 'ident-svc-%'");
    }

    private long createUser(Long githubId) {
        String username = "ident-svc-" + UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (username, password_hash, enabled, roles, permissions, github_id) "
                + "VALUES (?, '!', TRUE, 'user', '', ?)", username, githubId);
        return jdbc.queryForObject("SELECT id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private void addIdentity(long userId, String provider, String providerUserId) {
        jdbc.update("INSERT INTO user_identities (user_id, provider, provider_user_id) VALUES (?, ?, ?)",
                userId, provider, providerUserId);
    }

    @Test
    void listReturnsUsersIdentities() {
        long userId = createUser(123L);
        addIdentity(userId, "github", "123");
        addIdentity(userId, "discord", "snow-1");

        assertThat(service.listForUser(userId))
                .extracting("provider")
                .containsExactlyInAnyOrder("github", "discord");
    }

    @Test
    void unbindRemovesNonLastIdentity() {
        long userId = createUser(123L);
        addIdentity(userId, "github", "123");
        addIdentity(userId, "discord", "snow-1");

        var remaining = service.unbind(userId, "discord");

        assertThat(remaining).extracting("provider").containsExactly("github");
    }

    @Test
    void unbindingLastIdentityIsBlocked() {
        long userId = createUser(123L);
        addIdentity(userId, "github", "123");

        assertThatThrownBy(() -> service.unbind(userId, "github"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("唯一的登录方式");

        // 仍在——没被删
        assertThat(service.listForUser(userId)).hasSize(1);
    }

    @Test
    void unbindingProviderNotOwnedIsRejected() {
        long userId = createUser(123L);
        addIdentity(userId, "github", "123");
        addIdentity(userId, "discord", "snow-1");

        assertThatThrownBy(() -> service.unbind(userId, "google"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unbindingGithubClearsGithubIdColumnToPreventBackfillRevival() {
        long userId = createUser(999L);
        addIdentity(userId, "github", "999");
        addIdentity(userId, "discord", "snow-1"); // 保证 github 不是最后一种

        service.unbind(userId, "github");

        Long githubId = jdbc.queryForObject(
                "SELECT github_id FROM user_accounts WHERE id = ?", Long.class, userId);
        assertThat(githubId)
                .as("解绑 github 必须清空 github_id 列，否则 schema.sql 回填会复活该身份")
                .isNull();
    }

    @Test
    void unbindEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(delete("/api/user-center/identities/github"))
                .andExpect(status().isUnauthorized());
    }

    // ===================== M2b 绑定 =====================

    @Test
    void bindAttachesIdentityToExistingAccountWithoutCreatingOne() {
        long userId = createUser(null);
        addIdentity(userId, "github", "gh-" + userId);

        var after = service.bind(userId, "discord", "dc-" + userId, "a@e.com", "Nick");

        assertThat(after).hasSize(2);
        assertThat(after).extracting(v -> v.provider()).containsExactlyInAnyOrder("github", "discord");
        // 绑定不建号
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_accounts WHERE username LIKE 'ident-svc-%'", Integer.class)).isEqualTo(1);
    }

    @Test
    void bindRejectsIdentityAlreadyOwnedByAnotherAccount() {
        long owner = createUser(null);
        addIdentity(owner, "github", "gh-owner-" + owner);
        addIdentity(owner, "discord", "shared-discord-id");

        long other = createUser(null);
        addIdentity(other, "github", "gh-other-" + other);

        // 这正是"先 GA 再做 M2b"的死局：分叉账号占着身份，本尊补绑不进来。
        // 必须给出可辨识的 code，而不是 500。
        assertThatThrownBy(() -> service.bind(other, "discord", "shared-discord-id", null, null))
                .isInstanceOf(UserIdentityService.IdentityAlreadyBoundException.class)
                .satisfies(e -> assertThat(
                        ((UserIdentityService.IdentityAlreadyBoundException) e).errorCode()).isEqualTo("bind_taken"));
    }

    @Test
    void bindRejectsSecondIdentityOfSameProvider() {
        long userId = createUser(null);
        addIdentity(userId, "github", "gh-" + userId);
        addIdentity(userId, "discord", "dc-a-" + userId);

        assertThatThrownBy(() -> service.bind(userId, "discord", "dc-b-" + userId, null, null))
                .isInstanceOf(UserIdentityService.IdentityAlreadyBoundException.class)
                .satisfies(e -> assertThat(
                        ((UserIdentityService.IdentityAlreadyBoundException) e).errorCode()).isEqualTo("bind_duplicate"));
    }

    @Test
    void rebindingOwnIdentityIsReportedDistinctly() {
        long userId = createUser(null);
        addIdentity(userId, "github", "gh-" + userId);
        addIdentity(userId, "discord", "dc-" + userId);

        assertThatThrownBy(() -> service.bind(userId, "discord", "dc-" + userId, null, null))
                .isInstanceOf(UserIdentityService.IdentityAlreadyBoundException.class)
                .satisfies(e -> assertThat(
                        ((UserIdentityService.IdentityAlreadyBoundException) e).errorCode())
                        .isEqualTo("bind_already_yours"));
    }

    @Test
    void bindingGithubBackfillsGithubIdColumn() {
        // 与 unbind 清空 github_id 对称：贡献归属和 /u/{githubId} 都依赖这列
        long userId = createUser(null);
        addIdentity(userId, "discord", "dc-" + userId);

        service.bind(userId, "github", "114514", "a@e.com", "Nick");

        assertThat(jdbc.queryForObject(
                "SELECT github_id FROM user_accounts WHERE id = ?", Long.class, userId)).isEqualTo(114514L);
    }
}
