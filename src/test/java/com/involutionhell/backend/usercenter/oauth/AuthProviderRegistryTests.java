package com.involutionhell.backend.usercenter.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.junit.jupiter.api.Test;

/**
 * provider 注册表的契约。这是"provider 名"在后端的单一真相源，
 * 接入新 provider 只应新增一个 AuthProvider 实现，不改任何已有文件。
 */
class AuthProviderRegistryTests {

    /** 最小实现，只用来验证注册表本身的行为。 */
    private record StubProvider(String key, boolean verified) implements AuthProvider {
        @Override public AuthRequest newRequest() {
            throw new UnsupportedOperationException("测试不发起真实 OAuth");
        }
        @Override public String redirectUri() {
            return "http://cb/" + key;
        }
        @Override public boolean isEmailVerified(AuthUser user) {
            return verified;
        }
    }

    @Test
    void findsRegisteredProviderCaseInsensitively() {
        var registry = new AuthProviderRegistry(List.of(new StubProvider("discord", true)));
        assertThat(registry.find("discord")).isPresent();
        assertThat(registry.find("DISCORD")).as("URL 路径段大小写不该影响查找").isPresent();
    }

    @Test
    void unknownProviderReturnsEmptyInsteadOfThrowing() {
        // 调用方据此重定向到错误页；抛异常会变成 500
        var registry = new AuthProviderRegistry(List.of(new StubProvider("github", true)));
        assertThat(registry.find("myspace")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void duplicateKeysFailAtStartupRatherThanPickingOneAtRandom() {
        // 两个同名实现 = 行为不确定（谁生效取决于 bean 顺序）。启动即炸好过线上随机命中。
        assertThatThrownBy(() -> new AuthProviderRegistry(
                List.of(new StubProvider("discord", true), new StubProvider("discord", false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复的 AuthProvider key");
    }

    @Test
    void realProvidersAreRegisteredWithExpectedEmailTrust() {
        var registry = new AuthProviderRegistry(List.of(
                new GithubAuthProvider("id", "secret", "http://cb/github"),
                new DiscordAuthProvider("id", "secret", "http://cb/discord")));

        assertThat(registry.keys()).containsExactlyInAnyOrder("github", "discord");
        // GitHub 的 primary email 必然已验证 → 可用于自动关联
        assertThat(registry.find("github").orElseThrow().isEmailVerified(AuthUser.builder().build())).isTrue();
        // Discord 未带 verified 标记时必须保守判为未验证，否则是账号接管向量
        assertThat(registry.find("discord").orElseThrow().isEmailVerified(AuthUser.builder().build())).isFalse();
    }

    @Test
    void unconfiguredProviderRejectsRequestCreation() {
        // 只配一半（有 id 无 secret）也要在发起阶段就挡住，而不是到 token 交换才失败
        var provider = new DiscordAuthProvider("id", "", "http://cb/discord");
        assertThatThrownBy(provider::newRequest)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未配置");
    }
}
