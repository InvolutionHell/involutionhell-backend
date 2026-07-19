package com.involutionhell.backend.usercenter.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.fastjson.JSONObject;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import org.junit.jupiter.api.Test;

/**
 * Discord source 的可离线验证部分：/users/@me 响应 → AuthUser 的映射，
 * 以及 authorize URL 的构造。token 交换与真实 HTTP 需线上 Discord App 冒烟验证。
 */
class AuthDiscordRequestTests {

    private final AuthToken token = AuthToken.builder().accessToken("tok").build();

    @Test
    void mapsDiscordUserToAuthUser() {
        JSONObject u = new JSONObject();
        u.put("id", "930000000000000001");
        u.put("username", "alice");
        u.put("global_name", "Alice L");
        u.put("avatar", "abc123");
        u.put("email", "alice@example.com");

        AuthUser user = AuthDiscordRequest.mapUser(u, token);

        assertThat(user.getUuid()).isEqualTo("930000000000000001"); // provider_user_id
        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getNickname()).isEqualTo("Alice L");        // global_name 优先
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getAvatar())
                .isEqualTo("https://cdn.discordapp.com/avatars/930000000000000001/abc123.png");
        assertThat(user.getSource()).isEqualTo("DISCORD");
    }

    @Test
    void fallsBackToUsernameWhenGlobalNameMissing() {
        JSONObject u = new JSONObject();
        u.put("id", "1");
        u.put("username", "bob");
        // 无 global_name、无 avatar
        AuthUser user = AuthDiscordRequest.mapUser(u, token);
        assertThat(user.getNickname()).isEqualTo("bob");
        assertThat(user.getAvatar()).isNull();
    }

    @Test
    void throwsWhenIdMissing() {
        JSONObject u = new JSONObject();
        u.put("username", "no-id");
        assertThatThrownBy(() -> AuthDiscordRequest.mapUser(u, token))
                .isInstanceOf(AuthException.class);
    }

    @Test
    void authorizeUrlCarriesDiscordScopeAndClientId() {
        AuthDiscordRequest req = new AuthDiscordRequest(AuthConfig.builder()
                .clientId("cid-123").clientSecret("sec").redirectUri("https://e/cb").build());

        String url = req.authorize("state-xyz");

        assertThat(url)
                .startsWith("https://discord.com/oauth2/authorize")
                .contains("client_id=cid-123")
                .contains("response_type=code")
                .contains("state=state-xyz");
        // scope 空格必须预编码成 %20（裸空格进 Location 头不安全）
        assertThat(url).contains("scope=identify%20email");
    }
}
