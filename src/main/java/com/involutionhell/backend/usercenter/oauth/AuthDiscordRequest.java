package com.involutionhell.backend.usercenter.oauth;

import com.alibaba.fastjson.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.enums.AuthUserGender;
import me.zhyd.oauth.exception.AuthException;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthDefaultRequest;
import me.zhyd.oauth.utils.UrlBuilder;

/**
 * Discord 的 JustAuth AuthRequest。token 交换与拉用户信息用标准 HttpClient 自己发，
 * 完全掌控 form-encoded body 与 Bearer 头（Discord 严格要求
 * application/x-www-form-urlencoded）。响应用 fastjson 解析，与 JustAuth 内部一致。
 * 只实现登录必需的 authorize / getAccessToken / getUserInfo。
 */
public class AuthDiscordRequest extends AuthDefaultRequest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AuthDiscordRequest(AuthConfig config) {
        super(config, DiscordAuthSource.DISCORD);
    }

    @Override
    public String authorize(String state) {
        return UrlBuilder.fromBaseUrl(source.authorize())
                .queryParam("response_type", "code")
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", config.getRedirectUri())
                .queryParam("state", getRealState(state))
                // identify 拿 id/username/avatar，email 拿邮箱（对齐 github 的资料字段）。
                // JustAuth 的 UrlBuilder 不做 URL 编码，scope 里的空格必须预编码成 %20，
                // 否则裸空格进 Location 头会被 Tomcat 拒/行为未定义。
                .queryParam("scope", "identify%20email")
                .build();
    }

    @Override
    protected AuthToken getAccessToken(AuthCallback authCallback) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.getClientId());
        form.put("client_secret", config.getClientSecret());
        form.put("grant_type", "authorization_code");
        form.put("code", authCallback.getCode());
        form.put("redirect_uri", config.getRedirectUri());

        JSONObject json = postForm(source.accessToken(), form);
        String accessToken = json.getString("access_token");
        if (accessToken == null) {
            throw new AuthException("Discord token 交换失败: " + json.toJSONString());
        }
        return AuthToken.builder()
                .accessToken(accessToken)
                .refreshToken(json.getString("refresh_token"))
                .scope(json.getString("scope"))
                .tokenType(json.getString("token_type"))
                .expireIn(json.getIntValue("expires_in"))
                .build();
    }

    @Override
    protected AuthUser getUserInfo(AuthToken authToken) {
        JSONObject u = getBearer(source.userInfo(), authToken.getAccessToken());
        return mapUser(u, authToken);
    }

    /**
     * Discord /users/@me 响应 → AuthUser。无 HTTP、无副作用，正式路径与单测共用。
     * 抛 AuthException（缺 id）交给调用方按登录失败处理。
     */
    static AuthUser mapUser(JSONObject u, AuthToken authToken) {
        String id = u == null ? null : u.getString("id");
        if (id == null) {
            throw new AuthException("Discord 用户信息拉取失败: " + (u == null ? "null" : u.toJSONString()));
        }
        String username = u.getString("username");
        // global_name 是 Discord 新版展示名，缺失回退 username
        String nickname = u.getString("global_name") != null ? u.getString("global_name") : username;
        String avatarHash = u.getString("avatar");
        String avatar = avatarHash == null ? null
                : "https://cdn.discordapp.com/avatars/" + id + "/" + avatarHash + ".png";
        return AuthUser.builder()
                .rawUserInfo(u)
                .uuid(id)                 // provider_user_id = Discord snowflake
                .username(username)
                .nickname(nickname)
                .avatar(avatar)
                .email(u.getString("email"))
                .gender(AuthUserGender.UNKNOWN)
                .token(authToken)
                .source(DiscordAuthSource.DISCORD.toString())
                .build();
    }

    private JSONObject postForm(String url, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req);
    }

    private JSONObject getBearer(String url, String accessToken) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(req);
    }

    private JSONObject send(HttpRequest req) {
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return JSONObject.parseObject(resp.body());
        } catch (Exception e) {
            throw new AuthException("Discord OAuth HTTP 调用失败: " + e.getMessage(), e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
