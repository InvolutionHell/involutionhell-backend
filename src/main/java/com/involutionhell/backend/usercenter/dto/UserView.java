package com.involutionhell.backend.usercenter.dto;

import com.involutionhell.backend.usercenter.model.UserAccount;
import java.util.Set;

public record UserView(
        Long id,
        String username,
        String displayName,
        boolean enabled,
        Set<String> roles,
        Set<String> permissions
) {

    /**
     * 将用户领域对象转换为对外返回的视图对象。
     */
    public static UserView from(UserAccount userAccount) {
        return new UserView(
                userAccount.id(),
                userAccount.username(),
                userAccount.displayName(),
                userAccount.enabled(),
                userAccount.roles(),
                userAccount.permissions()
        );
    }
}
