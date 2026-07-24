package com.involutionhell.backend.usercenter.model;

/**
 * 待邮箱验证的注册意图（OTP 流程）。第三方登录首次、无法自动关联时创建，
 * 存进 RegistrationService 的进程内缓存（Caffeine，短 TTL），验证码通过后才
 * 据此建号/关联。不落库——用户放弃就随 TTL 自然消失。
 */
public record PendingRegistration(
        String provider,
        String providerUserId,
        String displayName,
        String avatarUrl,
        String providerEmail,   // provider 给的邮箱（预填用），用户可在验证步改成别的
        Long githubId
) {
}
