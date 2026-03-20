package com.involutionhell.backend.usercenter.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.security.UserStpInterface;
import com.involutionhell.backend.usercenter.service.PasswordService;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InMemoryUserAccountRepositoryTests {

    private final PasswordService passwordService = new PasswordService();
    private final InMemoryUserAccountRepository repository = new InMemoryUserAccountRepository(passwordService);

    @Test
    void findByUsernameSupportsCaseInsensitiveQuery() {
        assertThat(repository.findByUsername("ADMIN")).isPresent();
        assertThat(repository.findByUsername(" alice ")).isPresent();
        assertThat(repository.findByUsername("")).isEmpty();
        assertThat(repository.findByUsername(null)).isEmpty();
    }

    @Test
    void findAllReturnsSeedUsersSortedById() {
        assertThat(repository.findAll())
                .extracting(UserAccount::id)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void updateAuthorizationUpdatesStoredUser() {
        UserAccount updated = repository.updateAuthorization(
                2L,
                Set.of(" Editor "),
                Set.of(" USER:CENTER:READ ")
        );

        assertThat(updated.roles()).containsExactly("editor");
        assertThat(updated.permissions()).containsExactly("user:center:read");
        assertThat(repository.findById(2L)).contains(updated);
    }

    @Test
    void updateAuthorizationRejectsUnknownUser() {
        assertThatThrownBy(() -> repository.updateAuthorization(99L, Set.of("admin"), Set.of("user:center:read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户不存在: 99");
    }

    @Test
    void stpInterfaceReturnsRolesAndPermissionsForExistingUser() {
        UserStpInterface stpInterface = new UserStpInterface(repository);

        assertThat(stpInterface.getRoleList(1L, "login")).contains("admin");
        assertThat(stpInterface.getPermissionList(1L, "login")).contains("user:center:manage");
    }

    @Test
    void stpInterfaceRejectsUnknownUser() {
        UserStpInterface stpInterface = new UserStpInterface(repository);

        assertThatThrownBy(() -> stpInterface.getPermissionList(999L, "login"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("登录用户不存在: 999");
    }
}
