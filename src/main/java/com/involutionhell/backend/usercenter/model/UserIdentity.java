package com.involutionhell.backend.usercenter.model;

import java.time.Instant;

/**
 * 第三方登录身份，对应 user_identities 表的一行。
 * 一个 UserAccount 可挂多个 provider 身份（每个 provider 至多一个）。
 */
public record UserIdentity(
        Long id,
        long userId,
        String provider,
        String providerUserId,
        String emailAtLink,
        String displayNameAtLink,
        Instant linkedAt,
        Instant lastLoginAt
) {
}
