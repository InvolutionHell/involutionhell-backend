package com.involutionhell.backend.usercenter.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.involutionhell.backend.usercenter.dto.UserView;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserAccountTests {

    @Test
    void constructorNormalizesRolesAndPermissions() {
        UserAccount account = new UserAccount(
                1L,
                "admin",
                "hash",
                "管理员",
                true,
                Set.of(" Admin ", "admin", "USER"),
                Set.of(" user:profile:read ", "USER:PROFILE:READ", "user:center:read")
        );

        assertThat(account.roles()).containsExactlyInAnyOrder("admin", "user");
        assertThat(account.permissions()).containsExactlyInAnyOrder("user:profile:read", "user:center:read");
    }

    @Test
    void withAuthorizationCreatesNewNormalizedSnapshot() {
        UserAccount account = new UserAccount(
                1L,
                "admin",
                "hash",
                "管理员",
                true,
                Set.of("admin"),
                Set.of("user:profile:read")
        );

        UserAccount updated = account.withAuthorization(Set.of(" Reviewer "), Set.of(" USER:CENTER:READ "));

        assertThat(updated.roles()).containsExactly("reviewer");
        assertThat(updated.permissions()).containsExactly("user:center:read");
        assertThat(updated.id()).isEqualTo(account.id());
    }

    @Test
    void userViewFromConvertsAccountToView() {
        UserAccount account = new UserAccount(
                2L,
                "alice",
                "hash",
                "普通用户",
                true,
                Set.of("user"),
                Set.of("user:profile:read")
        );

        UserView view = UserView.from(account);

        assertThat(view.id()).isEqualTo(2L);
        assertThat(view.username()).isEqualTo("alice");
        assertThat(view.roles()).containsExactly("user");
    }
}
