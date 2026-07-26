package com.involutionhell.backend.usercenter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
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

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private com.involutionhell.backend.usercenter.repository.UserIdentityRepository userIdentityRepository;

    @InjectMocks
    private AuthService authService;

    /**
     * identity 双写默认：缺行（Optional.empty）→ ensureIdentity 走 insert 路径。
     * lenient 因为账号密码登录相关测试不触及 identity 分支。
     */
    @org.junit.jupiter.api.BeforeEach
    void stubIdentityLookupEmpty() {
        org.mockito.Mockito.lenient()
                .when(userIdentityRepository.findByProviderAndProviderUserId(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        // 默认无邮箱匹配 → 走建新号路径；自动关联测试单独覆盖。
        org.mockito.Mockito.lenient()
                .when(userAccountRepository.findByEmail(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of());
    }

    /**
     * 造 Discord AuthUser（带 rawUserInfo.verified，供已验证邮箱自动关联测试）。
     */
    private AuthUser discordUser(String uuid, String email, boolean emailVerified) {
        AuthUser user = mock(AuthUser.class);
        when(user.getUuid()).thenReturn(uuid);
        when(user.getNickname()).thenReturn("dc-" + uuid);
        when(user.getAvatar()).thenReturn(null);
        when(user.getEmail()).thenReturn(email);
        com.alibaba.fastjson.JSONObject raw = new com.alibaba.fastjson.JSONObject();
        raw.put("verified", emailVerified);
        when(user.getRawUserInfo()).thenReturn(raw);
        return user;
    }

    // =============================================
    // 辅助方法
    // =============================================

    /** 创建一个已启用的标准用户。 */
    private UserAccount enabledUser(Long id, String username, String passwordHash) {
        return new UserAccount(id, username, passwordHash, "显示名称", true,
                Set.of("user"), Set.of("user:profile:read"), null, null, null, null);
    }

    /** 创建一个已停用的用户。 */
    private UserAccount disabledUser(Long id, String username) {
        return new UserAccount(id, username, "hash", "显示名称", false,
                Set.of("user"), Set.of(), null, null, null, null);
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

    /**
     * INV-003 lazy upgrade：legacy SHA-256 hash 用户登录成功后，应被就地升级为 bcrypt。
     */
    @Test
    void loginUpgradesLegacyHashAfterSuccessfulMatch() {
        String legacyHash = "ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d";
        String newBcryptHash = "$2b$10$newbcryptsaltsaltsaltsaltsaltsaltsaltsaltsaltsaltsalts";
        UserAccount account = enabledUser(1L, "alice", legacyHash);
        when(userCenterService.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwordService.matches("Alice@123", legacyHash)).thenReturn(true);
        when(passwordService.isLegacyHash(legacyHash)).thenReturn(true);
        when(passwordService.hash("Alice@123")).thenReturn(newBcryptHash);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-abc");

            authService.login(new LoginRequest("alice", "Alice@123"));

            // 关键：登录成功路径必须触发 lazy upgrade 写回
            verify(userAccountRepository).updatePasswordHash(1L, newBcryptHash);
        }
    }

    /**
     * INV-003 lazy upgrade 容错：repository 写失败时不能阻断登录。
     */
    @Test
    void loginStillSucceedsWhenLazyUpgradeWriteFails() {
        String legacyHash = "ad89b64d66caa8e30e5d5ce4a9763f4ecc205814c412175f3e2c50027471426d";
        UserAccount account = enabledUser(1L, "alice", legacyHash);
        when(userCenterService.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwordService.matches("Alice@123", legacyHash)).thenReturn(true);
        when(passwordService.isLegacyHash(legacyHash)).thenReturn(true);
        when(passwordService.hash("Alice@123")).thenReturn("$2b$10$newhash");
        // 模拟 DB 抖动：updatePasswordHash 抛异常
        org.mockito.Mockito.doThrow(new RuntimeException("simulated DB hiccup"))
                .when(userAccountRepository).updatePasswordHash(any(), any());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-abc");

            // 不阻断登录：仍返回有效 LoginResponse
            LoginResponse response = authService.login(new LoginRequest("alice", "Alice@123"));
            assertThat(response.tokenValue()).isEqualTo("token-abc");
        }
    }

    /**
     * INV-003 反向：bcrypt 用户登录路径不应该触发 lazy upgrade（已经是 bcrypt 了）。
     */
    @Test
    void loginDoesNotUpgradeWhenHashIsAlreadyBcrypt() {
        String bcryptHash = "$2b$10$alreadybcrypt$alreadybcrypt$alreadybcrypt$alreadybcryptxx";
        UserAccount account = enabledUser(1L, "alice", bcryptHash);
        when(userCenterService.findByUsername("alice")).thenReturn(Optional.of(account));
        when(passwordService.matches("Alice@123", bcryptHash)).thenReturn(true);
        when(passwordService.isLegacyHash(bcryptHash)).thenReturn(false);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-abc");

            authService.login(new LoginRequest("alice", "Alice@123"));

            // 不应该调 hash() 也不应该调 updatePasswordHash()
            verify(passwordService, org.mockito.Mockito.never()).hash(any());
            verify(userAccountRepository, org.mockito.Mockito.never())
                    .updatePasswordHash(any(), any());
        }
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
                "https://new-avatar.url", "new@github.com", 12345L, null
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
    // loginByProvider() - identity 双写（M1）
    // =============================================

    @Test
    void newUserGetsIdentityInserted() {
        AuthUser ghUser = githubUser("12345", "Nick", null, null);
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenReturn(enabledUser(10L, "github_12345", "hash"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
            authService.loginByGithub(ghUser);
        }

        org.mockito.ArgumentCaptor<com.involutionhell.backend.usercenter.model.UserIdentity> cap =
                org.mockito.ArgumentCaptor.forClass(com.involutionhell.backend.usercenter.model.UserIdentity.class);
        verify(userIdentityRepository).insert(cap.capture());
        assertThat(cap.getValue().userId()).isEqualTo(10L);
        assertThat(cap.getValue().provider()).isEqualTo("github");
        assertThat(cap.getValue().providerUserId()).isEqualTo("12345");
    }

    @Test
    void existingIdentityRefreshesLastLoginInsteadOfInserting() {
        AuthUser ghUser = githubUser("12345", "Nick", null, null);
        when(userCenterService.findByUsername("github_12345"))
                .thenReturn(Optional.of(enabledUser(10L, "github_12345", "hash")));
        when(userCenterService.updateProfile(any(), any(), any(), any(), any()))
                .thenReturn(enabledUser(10L, "github_12345", "hash"));
        // 该 provider 身份已存在 → 不应再 insert，只刷新 last_login_at
        when(userIdentityRepository.findByProviderAndProviderUserId("github", "12345"))
                .thenReturn(Optional.of(new com.involutionhell.backend.usercenter.model.UserIdentity(
                        7L, 10L, "github", "12345", null, null, null, null)));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
            authService.loginByGithub(ghUser);
        }

        // 已有 identity 行：刷新登录时间的同时把本次拿到的邮箱/名字传下去补空
        verify(userIdentityRepository).recordLogin(eq(7L), any(), any());
        verify(userIdentityRepository, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void identityWriteFailureDoesNotBlockLogin() {
        AuthUser ghUser = githubUser("12345", "Nick", null, null);
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenReturn(enabledUser(10L, "github_12345", "hash"));
        // identity 写入炸掉——不能阻断登录（与 INV-003 lazy upgrade 同策略）
        org.mockito.Mockito.doThrow(new RuntimeException("simulated identity write failure"))
                .when(userIdentityRepository).insert(any());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-xyz");
            LoginResponse response = authService.loginByGithub(ghUser);
            assertThat(response.tokenValue()).isEqualTo("token-xyz");
        }
    }

    // =============================================
    // loginByProvider() - 已验证邮箱自动关联（防分叉）
    // =============================================

    @Test
    void verifiedEmailAutoLinksToExistingAccountInsteadOfForking() {
        // Discord 首次登录，邮箱已验证且匹配到已有账号 → 挂靠，不建新号
        AuthUser dc = discordUser("snow-1", "alice@example.com", true);
        UserAccount existing = enabledUser(10L, "github_12345", "hash");
        when(userCenterService.findByUsername("discord_snow-1")).thenReturn(Optional.empty());
        when(userAccountRepository.findByEmail("alice@example.com"))
                .thenReturn(java.util.List.of(existing));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            LoginResponse res = authService.loginByProvider("discord", dc);
            assertThat(res.user().username()).isEqualTo("github_12345"); // 登入的是已有账号
        }

        // 不建新号；只给已有账号挂上 discord 身份
        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
        org.mockito.ArgumentCaptor<com.involutionhell.backend.usercenter.model.UserIdentity> cap =
                org.mockito.ArgumentCaptor.forClass(com.involutionhell.backend.usercenter.model.UserIdentity.class);
        verify(userIdentityRepository).insert(cap.capture());
        assertThat(cap.getValue().userId()).isEqualTo(10L);
        assertThat(cap.getValue().provider()).isEqualTo("discord");
    }

    @Test
    void unverifiedEmailDoesNotAutoLinkAndCreatesNewAccount() {
        // 邮箱未验证 → 绝不按邮箱关联，建新号
        AuthUser dc = discordUser("snow-2", "alice@example.com", false);
        when(userCenterService.findByUsername("discord_snow-2")).thenReturn(Optional.empty());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenReturn(enabledUser(20L, "discord_snow-2", "hash"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            authService.loginByProvider("discord", dc);
        }

        // 未验证：不查邮箱关联，直接建新号
        verify(userCenterService).createUser(any());
        verify(userAccountRepository, org.mockito.Mockito.never()).findByEmail(any());
    }

    @Test
    void verifiedEmailWithNoMatchCreatesNewAccount() {
        AuthUser dc = discordUser("snow-3", "new@example.com", true);
        when(userCenterService.findByUsername("discord_snow-3")).thenReturn(Optional.empty());
        when(userAccountRepository.findByEmail("new@example.com")).thenReturn(java.util.List.of());
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenReturn(enabledUser(30L, "discord_snow-3", "hash"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            authService.loginByProvider("discord", dc);
        }
        verify(userCenterService).createUser(any());
    }

    @Test
    void ambiguousEmailMatchDoesNotAutoLink() {
        // 同邮箱命中多个账号（异常）→ 保守建新号，绝不猜挂
        AuthUser dc = discordUser("snow-4", "dup@example.com", true);
        when(userCenterService.findByUsername("discord_snow-4")).thenReturn(Optional.empty());
        when(userAccountRepository.findByEmail("dup@example.com"))
                .thenReturn(java.util.List.of(enabledUser(1L, "a", "h"), enabledUser(2L, "b", "h")));
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenReturn(enabledUser(40L, "discord_snow-4", "hash"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            authService.loginByProvider("discord", dc);
        }
        verify(userCenterService).createUser(any());
    }

    @Test
    void githubLoginAutoLinksToNonGithubAccountAndBackfillsGithubId() {
        // 反向：先 Discord 注册（账号 github_id=null），后用 GitHub 同邮箱登录 → 挂靠 + 补 github_id
        AuthUser gh = githubUser("555", "Nick", null, "alice@example.com");
        UserAccount discordFirst = new UserAccount(50L, "discord_snow-x", "hash", "显示名", true,
                Set.of("user"), Set.of(), null, "alice@example.com", null /*github_id null*/, null);
        when(userCenterService.findByUsername("github_555")).thenReturn(Optional.empty());
        when(userAccountRepository.findByEmail("alice@example.com"))
                .thenReturn(java.util.List.of(discordFirst));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            authService.loginByProvider("github", gh);
        }

        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
        verify(userAccountRepository).setGithubIdIfAbsent(50L, 555L);
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
