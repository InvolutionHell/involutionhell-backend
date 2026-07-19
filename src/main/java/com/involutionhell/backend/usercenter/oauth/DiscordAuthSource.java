package com.involutionhell.backend.usercenter.oauth;

import me.zhyd.oauth.config.AuthSource;
import me.zhyd.oauth.request.AuthDefaultRequest;

/**
 * Discord OAuth2 source。JustAuth 1.16.6 内置了 GitHub/Google/Microsoft 但没有
 * Discord，按 JustAuth 的自定义 source 约定补一个（ADR-001 M3）。
 * 端点见 https://discord.com/developers/docs/topics/oauth2。
 */
public enum DiscordAuthSource implements AuthSource {
    DISCORD;

    @Override
    public String authorize() {
        return "https://discord.com/oauth2/authorize";
    }

    @Override
    public String accessToken() {
        return "https://discord.com/api/oauth2/token";
    }

    @Override
    public String userInfo() {
        return "https://discord.com/api/users/@me";
    }

    @Override
    public Class<? extends AuthDefaultRequest> getTargetClass() {
        return AuthDiscordRequest.class;
    }
}
