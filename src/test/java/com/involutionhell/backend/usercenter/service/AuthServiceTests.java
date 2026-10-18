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

    private AuthService authService;

    @Mock
    private RegistrationService registrationService;

    /** 登入已有账号的结局；新用户结局（NeedsRegistration）在这里抛 ClassCastException。 */
    private LoginResponse loginAs(String provider, AuthUser u) {
        return ((AuthService.LoggedIn) authService.loginByProvider(provider, u)).response();
    }

    /**
     * 用**真实**的 AuthProviderRegistry + 真实 provider 实例，而不是 mock：
     * "该 provider 的邮箱算不算已验证"是自动关联的安全判据，这里要测真逻辑。
     * client-id/secret 随便给，本测试不发起真实 OAuth 请求。
     */
    @org.junit.jupiter.api.BeforeEach
    void wireAuthService() {
        var registry = new com.involutionhell.backend.usercenter.oauth.AuthProviderRegistry(java.util.List.of(
                new com.involutionhell.backend.usercenter.oauth.GithubAuthProvider("id", "secret", "http://cb/github"),
                new com.involutionhell.backend.usercenter.oauth.DiscordAuthProvider("id", "secret", "http://cb/discord")));
        authService = new AuthService(userCenterService, passwordService,
                userAccountRepository, userIdentityRepository, registry, registrationService);
        org.mockito.Mockito.lenient().when(registrationService.begin(any())).thenReturn("pid-1");
    }

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
        // lenient：身份命中路径不验邮箱，rawUserInfo 的 stub 用不到
        AuthUser user = mock(AuthUser.class,
                org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
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
    // loginByProvider() - 新用户进注册 / 老用户登入
    // =============================================

    /** 新用户结局：断言没建号、没挂身份、没登入，返回交给 RegistrationService 的注册意图。 */
    private com.involutionhell.backend.usercenter.model.PendingRegistration expectRegistration(String provider, AuthUser u) {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            assertThat(authService.loginByProvider(provider, u))
                    .isEqualTo(new AuthService.NeedsRegistration("pid-1"));
            stpUtil.verifyNoInteractions();
        }
        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
        verify(userIdentityRepository, org.mockito.Mockito.never()).insert(any());
        var cap = org.mockito.ArgumentCaptor.forClass(com.involutionhell.backend.usercenter.model.PendingRegistration.class);
        verify(registrationService).begin(cap.capture());
        return cap.getValue();
    }

    @Test
    void newGithubUserStartsRegistrationInsteadOfCreatingAccount() {
        AuthUser ghUser = githubUser("12345", "GitHubNick", "https://avatar.url", "user@github.com");
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.empty());

        assertThat(expectRegistration("github", ghUser)).isEqualTo(new com.involutionhell.backend.usercenter.model.PendingRegistration(
                "github", "12345", "GitHubNick", "https://avatar.url", "user@github.com", 12345L));
    }

    @Test
    void nonNumericGithubUuidGivesNullGithubIdInPending() {
        AuthUser ghUser = githubUser("not-a-number", "Nick", null, null);
        when(userCenterService.findByUsername("github_not-a-number")).thenReturn(Optional.empty());

        assertThat(expectRegistration("github", ghUser).githubId()).isNull();
    }

    @Test
    void pendingDisplayNameFallsBackToLoginWhenNicknameIsNull() {
        AuthUser ghUser = githubUser("12345", null, null, null);
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.empty());

        assertThat(expectRegistration("github", ghUser).displayName()).isEqualTo("github-login-12345");
    }

    @Test
    void loginByGithubFillsBlankProfileWhenUserAlreadyExists() {
        AuthUser ghUser = githubUser("12345", "UpdatedNick", "https://new-avatar.url", "new@github.com");

        UserAccount existing = enabledUser(10L, "github_12345", "hash");
        // updateProfile 只补空列（SQL 见 JdbcUserAccountRepositoryTests）：已有展示名保留，
        // 原本为空的头像/邮箱/github_id 被补上。
        UserAccount afterUpdate = new UserAccount(
                10L, "github_12345", "hash", "显示名称", true,
                Set.of("user"), Set.of("user:profile:read"),
                "https://new-avatar.url", "new@github.com", 12345L, null
        );
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.of(existing));
        when(userCenterService.updateProfile(10L, "UpdatedNick", "https://new-avatar.url", "new@github.com", 12345L))
                .thenReturn(afterUpdate);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-xyz");

            LoginResponse response = loginAs("github", ghUser);

            // 已有用户应走 updateProfile 而非 createUser
            verify(userCenterService).updateProfile(10L, "UpdatedNick", "https://new-avatar.url", "new@github.com", 12345L);
            assertThat(response.user().displayName()).isEqualTo("显示名称");
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

        assertThatThrownBy(() -> loginAs("github", ghUser))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("账号已被禁用");
    }

    @Test
    void existingIdentityRefreshesLastLoginInsteadOfInserting() {
        AuthUser ghUser = githubUser("12345", "Nick", null, null);
        when(userCenterService.updateProfile(any(), any(), any(), any(), any()))
                .thenReturn(enabledUser(10L, "github_12345", "hash"));
        // 该 provider 身份已存在 → 不应再 insert，只刷新 last_login_at
        when(userIdentityRepository.findByProviderAndProviderUserId("github", "12345"))
                .thenReturn(Optional.of(new com.involutionhell.backend.usercenter.model.UserIdentity(
                        7L, 10L, "github", "12345", null, null, null, null)));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
            loginAs("github", ghUser);
        }

        // 已有 identity 行：刷新登录时间的同时把本次拿到的邮箱/名字传下去补空
        verify(userIdentityRepository).recordLogin(eq(7L), any(), any());
        verify(userIdentityRepository, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void identityWriteFailureDoesNotBlockLogin() {
        AuthUser ghUser = githubUser("12345", "Nick", null, null);
        // 老账号（M0 回填没覆盖到）：按用户名找到，补身份行时 DB 炸掉——不能阻断登录
        when(userCenterService.findByUsername("github_12345")).thenReturn(Optional.of(enabledUser(10L, "github_12345", "hash")));
        when(userCenterService.updateProfile(any(), any(), any(), any(), any())).thenReturn(enabledUser(10L, "github_12345", "hash"));
        org.mockito.Mockito.doThrow(new RuntimeException("simulated identity write failure"))
                .when(userIdentityRepository).insert(any());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("token-xyz");
            LoginResponse response = loginAs("github", ghUser);
            assertThat(response.tokenValue()).isEqualTo("token-xyz");
        }
    }

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
            LoginResponse res = loginAs("discord", dc);
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
    void unverifiedEmailDoesNotAutoLinkAndStartsRegistration() {
        AuthUser dc = discordUser("snow-2", "alice@example.com", false);
        when(userCenterService.findByUsername("discord_snow-2")).thenReturn(Optional.empty());

        // 未验证邮箱只用来预填验证页，绝不拿去查挂靠
        assertThat(expectRegistration("discord", dc).providerEmail()).isEqualTo("alice@example.com");
        verify(userAccountRepository, org.mockito.Mockito.never()).findByEmail(any());
    }

    @Test
    void verifiedEmailWithNoMatchStartsRegistration() {
        AuthUser dc = discordUser("snow-3", "new@example.com", true);
        when(userCenterService.findByUsername("discord_snow-3")).thenReturn(Optional.empty());
        when(userAccountRepository.findByEmail("new@example.com")).thenReturn(java.util.List.of());

        expectRegistration("discord", dc);
    }

    @Test
    void ambiguousEmailMatchDoesNotAutoLink() {
        // 同邮箱命中多个账号（异常）→ 绝不猜挂，交给注册（验证后会因邮箱已占用而 409）
        AuthUser dc = discordUser("snow-4", "dup@example.com", true);
        when(userCenterService.findByUsername("discord_snow-4")).thenReturn(Optional.empty());
        when(userAccountRepository.findByEmail("dup@example.com"))
                .thenReturn(java.util.List.of(enabledUser(1L, "a", "h"), enabledUser(2L, "b", "h")));

        expectRegistration("discord", dc);
    }

    @Test
    void returningLoginNeverFillsAnUnverifiedEmail() {
        // 邮箱是自动挂靠的锚点：账号 email 为空时也不能用未验证邮箱补
        AuthUser dc = discordUser("snow-5", "victim@example.com", false);
        when(userIdentityRepository.findByProviderAndProviderUserId("discord", "snow-5"))
                .thenReturn(Optional.of(new com.involutionhell.backend.usercenter.model.UserIdentity(
                        9L, 10L, "discord", "snow-5", null, null, null, null)));
        when(userCenterService.updateProfile(any(), any(), any(), any(), any())).thenReturn(enabledUser(10L, "u", "h"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            loginAs("discord", dc);
        }
        verify(userCenterService).updateProfile(eq(10L), any(), any(), org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void completeRegistrationCreatesAccountWithVerifiedEmailAndIdentity() {
        var pending = new com.involutionhell.backend.usercenter.model.PendingRegistration(
                "discord", "snow-6", "Nick", "https://a", "prefill@example.com", null);
        when(passwordService.hash(any())).thenReturn("hash");
        when(userCenterService.createUser(any())).thenAnswer(inv -> {
            UserAccount a = inv.getArgument(0);
            assertThat(a.username()).isEqualTo("discord_snow-6");
            assertThat(a.email()).as("写的是本次验证过的邮箱，不是 provider 预填").isEqualTo("real@example.com");
            assertThat(a.roles()).containsExactly("user");
            return new UserAccount(60L, a.username(), a.passwordHash(), a.displayName(), true,
                    a.roles(), a.permissions(), a.avatarUrl(), a.email(), a.githubId(), null);
        });

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            assertThat(authService.completeRegistration(
                    new RegistrationService.VerifiedRegistration(pending, "real@example.com")).tokenValue()).isEqualTo("tok");
            stpUtil.verify(() -> StpUtil.login(60L));
        }
        var cap = org.mockito.ArgumentCaptor.forClass(com.involutionhell.backend.usercenter.model.UserIdentity.class);
        verify(userIdentityRepository).insert(cap.capture());
        assertThat(cap.getValue().userId()).isEqualTo(60L);
        assertThat(cap.getValue().providerUserId()).isEqualTo("snow-6");
    }

    @Test
    void completeRegistrationRefusesEmailOfExistingAccountWithoutLinking() {
        when(userAccountRepository.findByEmail("alice@example.com")).thenReturn(java.util.List.of(enabledUser(2L, "alice", "h")));
        var pending = new com.involutionhell.backend.usercenter.model.PendingRegistration(
                "discord", "snow-7", "Nick", null, null, null);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> authService.completeRegistration(
                    new RegistrationService.VerifiedRegistration(pending, "alice@example.com")))
                    .isInstanceOf(AuthService.EmailTakenException.class);
            stpUtil.verifyNoInteractions();
        }
        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
        verify(userIdentityRepository, org.mockito.Mockito.never()).insert(any());
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
            loginAs("github", gh);
        }

        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
        verify(userAccountRepository).setGithubIdIfAbsent(50L, 555L);
    }

    // =============================================
    // loginByProvider() - identity-first（M2b 绑定后不能按用户名/邮箱找人）
    // =============================================

    private static com.involutionhell.backend.usercenter.model.UserIdentity identity(
            long userId, String provider, String providerUserId) {
        return new com.involutionhell.backend.usercenter.model.UserIdentity(
                7L, userId, provider, providerUserId, null, null, null, null);
    }

    @Test
    void boundDiscordIdentityWithDifferentEmailLogsIntoOriginalAccount() {
        // github 账号绑了一个邮箱不同的 Discord。按用户名 discord_snow-b 查不到、按邮箱也匹配不上，
        // 身份优先之前这里会建第二个账号。
        AuthUser dc = discordUser("snow-b", "other@example.com", true);
        when(userIdentityRepository.findByProviderAndProviderUserId("discord", "snow-b"))
                .thenReturn(Optional.of(identity(10L, "discord", "snow-b")));
        when(userCenterService.updateProfile(eq(10L), any(), any(), eq("other@example.com"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(enabledUser(10L, "github_12345", "hash"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            LoginResponse res = loginAs("discord", dc);

            stpUtil.verify(() -> StpUtil.login(10L));
            assertThat(res.user().username()).isEqualTo("github_12345");
        }
        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
        verify(userCenterService, org.mockito.Mockito.never()).findByUsername(any());
        verify(userAccountRepository, org.mockito.Mockito.never()).findByEmail(any());
    }

    @Test
    void discordLoginNeverWritesGithubIdEvenWithNumericSnowflake() {
        // Discord id 也是纯数字，误当 github_id 写进去就会把别人的 /u/{githubId} 指过来
        String snowflake = "123456789012345678";
        when(userIdentityRepository.findByProviderAndProviderUserId("discord", snowflake))
                .thenReturn(Optional.of(identity(10L, "discord", snowflake)));
        when(userCenterService.updateProfile(eq(10L), any(), any(), any(), any()))
                .thenReturn(enabledUser(10L, "github_12345", "hash"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            loginAs("discord", discordUser(snowflake, null, false));
        }
        verify(userCenterService).updateProfile(eq(10L), any(), any(), any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void githubLoginAfterBindOnNonGithubAccountLandsThereWithoutCreatingAccount() {
        // 非 github_ 账号绑了 GitHub（bind 已写 github_id=555）。旧逻辑按 github_555 查 miss →
        // 建号 → 撞 UNIQUE(github_id)。这里让 createUser 抛约束异常，证明根本没走到它。
        AuthUser gh = githubUser("555", "Nick", null, null);
        when(userIdentityRepository.findByProviderAndProviderUserId("github", "555"))
                .thenReturn(Optional.of(identity(50L, "github", "555")));
        when(userCenterService.updateProfile(50L, "Nick", null, null, 555L))
                .thenReturn(new UserAccount(50L, "discord_snow-x", "hash", "显示名", true,
                        Set.of("user"), Set.of(), null, null, 555L, null));
        org.mockito.Mockito.lenient().when(userCenterService.createUser(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("UNIQUE(github_id)"));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");
            LoginResponse res = loginAs("github", gh);

            stpUtil.verify(() -> StpUtil.login(50L));
            assertThat(res.user().username()).isEqualTo("discord_snow-x");
        }
        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
    }

    @Test
    void autoLinkedIdentitySurvivesEmailChange() {
        // 第一次：Discord 已验证邮箱命中 github 账号 → 挂靠并写入身份行。
        // 第二次：Discord 那边换了邮箱 → 必须靠身份行找回同一账号，而不是再走邮箱匹配建新号。
        var stored = new java.util.concurrent.atomic.AtomicReference<
                com.involutionhell.backend.usercenter.model.UserIdentity>();
        org.mockito.Mockito.doAnswer(inv -> {
            com.involutionhell.backend.usercenter.model.UserIdentity i = inv.getArgument(0);
            stored.set(identity(i.userId(), i.provider(), i.providerUserId()));
            return stored.get();
        }).when(userIdentityRepository).insert(any());
        when(userIdentityRepository.findByProviderAndProviderUserId("discord", "snow-c"))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));

        UserAccount original = enabledUser(10L, "github_12345", "hash");
        when(userAccountRepository.findByEmail("alice@example.com")).thenReturn(java.util.List.of(original));
        when(userCenterService.updateProfile(eq(10L), any(), any(), eq("alice-new@example.com"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(original);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getTokenName).thenReturn("satoken");
            stpUtil.when(StpUtil::getTokenValue).thenReturn("tok");

            loginAs("discord", discordUser("snow-c", "alice@example.com", true));
            loginAs("discord", discordUser("snow-c", "alice-new@example.com", true));

            stpUtil.verify(() -> StpUtil.login(10L), org.mockito.Mockito.times(2));
        }
        verify(userCenterService, org.mockito.Mockito.never()).createUser(any());
        verify(userIdentityRepository).insert(any()); // 只在第一次挂靠时插一行
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
