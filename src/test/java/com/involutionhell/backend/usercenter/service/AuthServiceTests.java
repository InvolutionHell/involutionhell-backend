package com.involutionhell.backend.usercenter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import java.util.Optional;
import java.util.Set;
import me.zhyd.oauth.model.AuthUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AuthService 单元测试。
 * StpUtil 是静态工具类，使用 Mockito.mockStatic 进行隔离，避免依赖 Sa-Token 容器。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    @Mock
    private UserCenterService userCenterService;

    @Mock
    private PasswordService passwordService;

    @InjectMocks
    private AuthService authService;

    // =============================================
    // 辅助方法
    // =============================================

    /** 创建一个已启用的标准用户。 */
    private UserAccount enabledUser(Long id, String username, String passwordHash) {
        return new UserAccount(id, username, passwordHash, "显示名称", true,
                Set.of("user"), Set.of("user:profile:read"), null, null, null);
    }

    /** 创建一个已停用的用户。 */
    private UserAccount disabledUser(Long id, String username) {
        return new UserAccount(id, username, "hash", "显示名称", false,
                Set.of("user"), Set.of(), null, null, null);
    }

    /**
     * 创建 AuthUser Mock，模拟 JustAuth 返回的 GitHub 用户信息。
     * nickname 为 null 时 AuthService 回退使用 username 字段。
     */
    private AuthUser githubUser(String uuid, String nickname, String avatar, String email) {
        AuthUser user = mock(AuthUser.class);
        when(user.getUuid()).thenReturn(uuid);
        when(user.getNickname()).thenReturn(nickname);
        if (nickname == null) {
            when(user.getUsername()).thenReturn("github-login-" + uuid);
        }
        when(user.getAvatar()).thenReturn(avatar);
        when(user.getEmail()).thenReturn(email);
        return user;
    }

    // =============================================
    // login() - 账号密码登录
    // =============================================

    @Test
    void loginSucceedsWithCorrectCredentials() {
        UserAccount account = enabledUser(1L, "alice", "correct-hash");
        when(userCenterService.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwordService.matches("Alice@123", "correct-hash")).thenReturn(true);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-abc");

            LoginResponse response = authService.login(new LoginRequest("alice", "Alice@123"));

            // 验证 Sa-Token 登录以正确的用户 ID 被调用
            stpUtil.verify(() -> StpUtil.login(1L));
            assertThat(response.tokenName()).isEqualTo("satoken");
            assertThat(response.tokenValue()).isEqualTo("token-abc");
            assertThat(response.user().username()).isEqualTo("alice");
        }
    }

    @Test
    void loginThrowsWhenUsernameDoesNotExist() {
        when(userCenterService.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody", "pass")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void loginThrowsWhenAccountIsDisabled() {
        when(userCenterService.findByUsername("alice")).thenReturn(Optional.of(disabledUser(2L, "alice")));

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "Alice@123")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("账号已被禁用");
    }

    @Test
    void loginThrowsWhenPasswordIsWrong() {
        UserAccount account = enabledUser(1L, "alice", "correct-hash");
        when(userCenterService.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwordService.matches("wrong-pass", "correct-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong-pass")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名或密码错误");
    }

    // =============================================
    // loginByGithub() - GitHub OAuth 登录
    // =============================================

    @Test
    void loginByGithubAutoRegistersNewUser() {
        // GitHub UUID 为纯数字，可被解析为 Long
        AuthUser ghUser = githubUser("12345", "GitHubNick", "https://avatar.url", "user@github.com");

        UserAccount createdAccount = enabledUser(10L, "github_12345", "random-hash");
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("random-hash");
        when(userCenterService.createUser(any())).thenReturn(createdAccount);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-xyz");

            LoginResponse response = authService.loginByGithub(ghUser);

            // 应调用 createUser 而非 updateProfile
            verify(userCenterService).createUser(any());
            assertThat(response.user().username()).isEqualTo("github_12345");
        }
    }

    @Test
    void loginByGithubSetsCorrectGithubIdOnNewUser() {
        AuthUser ghUser = githubUser("99999", "Nick", null, null);

        when(userCenterService.findByUsername("github_99999")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenAnswer(inv -> {
            UserAccount arg = inv.getArgument(0);
            // 验证 githubId 被正确解析为 Long
            assertThat(arg.githubId()).isEqualTo(99999L);
            return enabledUser(10L, "github_99999", "hash");
        });

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
            authService.loginByGithub(ghUser);
        }
    }

    @Test
    void loginByGithubSetsNullGithubIdWhenUuidIsNotNumeric() {
        // GitHub UUID 非纯数字（极罕见，但代码中有 try-catch 处理）
        AuthUser ghUser = githubUser("not-a-number", "Nick", null, null);

        when(userCenterService.findByUsername("github_not-a-number")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenAnswer(inv -> {
            UserAccount arg = inv.getArgument(0);
            // UUID 无法解析为数字时，githubId 应为 null
            assertThat(arg.githubId()).isNull();
            return enabledUser(10L, "github_not-a-number", "hash");
        });

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
            authService.loginByGithub(ghUser);
        }
    }

    @Test
    void loginByGithubUsesUsernameAsFallbackWhenNicknameIsNull() {
        // GitHub 用户昵称为 null 时，回退使用 username 字段作为 displayName
        AuthUser ghUser = githubUser("12345", null, null, null);

        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenAnswer(inv -> {
            UserAccount arg = inv.getArgument(0);
            // displayName 应来自 getUsername()，即 "github-login-12345"
            assertThat(arg.displayName()).isEqualTo("github-login-12345");
            return enabledUser(10L, "github_12345", "hash");
        });

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
            authService.loginByGithub(ghUser);
        }
    }

    @Test
    void loginByGithubUpdatesProfileWhenUserAlreadyExists() {
        AuthUser ghUser = githubUser("12345", "UpdatedNick", "https://new-avatar.url", "new@github.com");

        UserAccount existing = enabledUser(10L, "github_12345", "hash");
        UserAccount afterUpdate = new UserAccount(
                10L, "github_12345", "hash", "UpdatedNick", true,
                Set.of("user"), Set.of("user:profile:read"),
                "https://new-avatar.url", "new@github.com", 12345L
        );
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.of(existing));
        when(userCenterService.updateProfile(10L, "UpdatedNick", "https://new-avatar.url", "new@github.com", 12345L))
                .thenReturn(afterUpdate);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-xyz");

            LoginResponse response = authService.loginByGithub(ghUser);

            // 已有用户应走 updateProfile 而非 createUser
            verify(userCenterService).updateProfile(10L, "UpdatedNick", "https://new-avatar.url", "new@github.com", 12345L);
            assertThat(response.user().displayName()).isEqualTo("UpdatedNick");
            assertThat(response.user().githubId()).isEqualTo(12345L);
        }
    }

    @Test
    void loginByGithubThrowsWhenExistingAccountIsDisabled() {
        AuthUser ghUser = githubUser("12345", "Nick", null, null);

        UserAccount disabledAccount = disabledUser(10L, "github_12345");
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.of(disabledAccount));
        // updateProfile 仍会被调用（刷新资料），但返回的账号仍是禁用状态
        when(userCenterService.updateProfile(any(), any(), any(), any(), any()))
                .thenReturn(disabledAccount);

        assertThatThrownBy(() -> authService.loginByGithub(ghUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("账号已被禁用");
    }

    @Test
    void loginByGithubThrowsWhenNewlyRegisteredAccountIsDisabled() {
        // 理论上不会发生（新注册账号默认启用），但防御性测试
        AuthUser ghUser = githubUser("12345", "Nick", null, null);

        UserAccount disabledAccount = disabledUser(10L, "github_12345");
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenReturn(disabledAccount);

        assertThatThrownBy(() -> authService.loginByGithub(ghUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("账号已被禁用");
    }

    // =============================================
    // logout() 和 currentUser()
    // =============================================

    @Test
    void logoutInvalidatesCurrentSession() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            authService.logout();

            // 验证 Sa-Token 的 logout 方法被正确调用
            stpUtil.verify(StpUtil::logout);
        }
    }

    @Test
    void currentUserDelegatesToUserCenterService() {
        UserView expectedView = new UserView(
                1L, "alice", "Alice", true,
                Set.of("user"), Set.of("user:profile:read"),
                null, null, null
        );
        when(userCenterService.currentUser()).thenReturn(expectedView);

        UserView result = authService.currentUser();

        assertThat(result).isSameAs(expectedView);
    }
}
