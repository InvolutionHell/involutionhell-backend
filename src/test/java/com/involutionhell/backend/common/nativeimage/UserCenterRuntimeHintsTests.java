package com.involutionhell.backend.common.nativeimage;

import static org.assertj.core.api.Assertions.assertThat;

import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserAuthorizationUpdateRequest;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

class UserCenterRuntimeHintsTests {

    @Test
    void registerHintsAddsReflectionEntriesForUserCenterTypes() {
        RuntimeHints hints = new RuntimeHints();

        new UserCenterRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(LoginRequest.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(LoginResponse.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(UserAuthorizationUpdateRequest.class).test(hints))
                .isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(UserView.class).test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection().onType(UserAccount.class).test(hints)).isTrue();
    }
}
