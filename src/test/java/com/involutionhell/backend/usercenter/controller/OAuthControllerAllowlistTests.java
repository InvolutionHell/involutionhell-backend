package com.involutionhell.backend.usercenter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.involutionhell.backend.usercenter.service.AuthService;
import org.junit.jupiter.api.Test;

/**
 * INV-008 回归：Discord 灰度闸的判定逻辑。
 *
 * 回调成功路径需要真实的 Discord code，MockMvc 到不了闸，所以这里直接测判定函数
 * （OAuthControllerIntegrationTests 覆盖到 INV-007 的 state 校验为止）。
 */
class OAuthControllerAllowlistTests {

    private final AuthService authService = mock(AuthService.class);
    private final OAuthController controller = new OAuthController(authService);

    private OAuthController withAllowlist(String raw) {
        controller.configureDiscordAllowlist(raw);
        return controller;
    }

    // ---------- 解析：畸形取值不能把闸变成"谁都进不去"或"谁都能进" ----------

    @Test
    void parsesCommaSeparatedIdsIgnoringWhitespaceAndEmptyEntries() {
        assertThat(OAuthController.parseAllowlist(" 111 , 222 ,, 333 ,"))
                .containsExactlyInAnyOrder("111", "222", "333");
    }

    @Test
    void stripsQuotesBecauseDockerEnvFileKeepsThem() {
        // docker-compose 的 env_file 不剥引号，AUTH_DISCORD_ALLOWLIST="123" 会带引号进来
        assertThat(OAuthController.parseAllowlist("\"123\",'456'"))
                .containsExactlyInAnyOrder("123", "456");
    }

    @Test
    void blankValuesYieldEmptySet() {
        assertThat(OAuthController.parseAllowlist(null)).isEmpty();
        assertThat(OAuthController.parseAllowlist("   ")).isEmpty();
        // 只剩逗号：Java 的 ",".split(",") 返回零长数组
        assertThat(OAuthController.parseAllowlist(",")).isEmpty();
    }

    // ---------- 闸的两种模式 ----------

    @Test
    void unconfiguredAllowlistOpensDiscordToEveryone() {
        // 不配 = GA。这是有意的 fail-open，启动日志会播报，别在此处改成拒绝。
        assertThat(withAllowlist("").discordAllowed("anyone")).isTrue();
        assertThat(withAllowlist(null).discordAllowed("anyone")).isTrue();
    }

    @Test
    void configuredButUnparseableRejectsEveryoneRatherThanOpeningUp() {
        // 配了值却解析不出 id（清列表时手滑留了个逗号）→ 错误方向必须选"没人能登"，
        // 绝不能塌缩成"所有人能登"。
        assertThat(withAllowlist(",").discordAllowed("111")).isFalse();
    }

    @Test
    void allowlistedIdPasses() {
        assertThat(withAllowlist("111,222").discordAllowed("222")).isTrue();
        verify(authService, never()).hasIdentity(eq("discord"), eq("222"));
    }

    @Test
    void nonAllowlistedNewUserIsRejected() {
        when(authService.hasIdentity("discord", "999")).thenReturn(false);
        assertThat(withAllowlist("111").discordAllowed("999")).isFalse();
    }

    @Test
    void blankUuidIsRejected() {
        assertThat(withAllowlist("111").discordAllowed(null)).isFalse();
        assertThat(withAllowlist("111").discordAllowed("  ")).isFalse();
    }

    // ---------- 闸保护的是"建新号"，不是把老用户锁在门外 ----------

    @Test
    void existingIdentityPassesEvenWhenNotAllowlisted() {
        // 灰度要拦的是新用户建号（OTP wiring 未完成）；已经有账号的人再登录不该被拦，
        // 否则他会被锁在自己的账号外面，且提示只说"敬请期待"。
        when(authService.hasIdentity("discord", "old-user")).thenReturn(true);
        assertThat(withAllowlist("111").discordAllowed("old-user")).isTrue();
    }

    @Test
    void identityLookupFailureDoesNotOpenTheGate() {
        // AuthService.hasIdentity 内部吞异常返 false，这里锁定"查不到就拒绝"的方向
        when(authService.hasIdentity("discord", "boom")).thenReturn(false);
        assertThat(withAllowlist("111").discordAllowed("boom")).isFalse();
    }
}
