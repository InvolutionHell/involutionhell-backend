package com.involutionhell.backend.usercenter.dto;

import com.involutionhell.backend.usercenter.model.UserIdentity;
import java.time.Instant;

/**
 * 设置页展示用的已绑定身份视图。不含 provider_user_id 等可标识第三方账号的字段，
 * 只暴露 provider、绑定/最近登录时间和绑定时的展示名。
 */
public record LinkedIdentityView(
        String provider,
        String displayNameAtLink,
        Instant linkedAt,
        Instant lastLoginAt
) {
    public static LinkedIdentityView from(UserIdentity i) {
        return new LinkedIdentityView(i.provider(), i.displayNameAtLink(), i.linkedAt(), i.lastLoginAt());
    }
}
