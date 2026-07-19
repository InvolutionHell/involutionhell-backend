package com.involutionhell.backend.usercenter.service;

import com.involutionhell.backend.usercenter.dto.LinkedIdentityView;
import com.involutionhell.backend.usercenter.model.UserIdentity;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import com.involutionhell.backend.usercenter.repository.UserIdentityRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录身份的读取与解绑（M2a）。绑定（新建第二 provider）走 M2b 的 OAuth 流程。
 */
@Service
public class UserIdentityService {

    private final UserIdentityRepository userIdentityRepository;
    private final UserAccountRepository userAccountRepository;

    public UserIdentityService(UserIdentityRepository userIdentityRepository,
                               UserAccountRepository userAccountRepository) {
        this.userIdentityRepository = userIdentityRepository;
        this.userAccountRepository = userAccountRepository;
    }

    public List<LinkedIdentityView> listForUser(long userId) {
        return userIdentityRepository.findByUserId(userId).stream()
                .map(LinkedIdentityView::from)
                .toList();
    }

    /**
     * 解绑指定 provider 身份。返回解绑后剩余身份列表。
     *
     * 两条安全规则：
     *   1. 不能解绑最后一种登录方式——否则用户可能永久锁死（OAuth 用户的随机密码
     *      不是可用登录方式，且无法可靠区分，故保守地只按"剩余身份数"判定，不把密码
     *      算作兜底；代价是纯密码用户暂时不能解绑其唯一绑定，安全方向优先）。
     *   2. 解绑 github 时同步清空 user_accounts.github_id——否则 schema.sql 启动回填
     *      会按残留列值把该身份静默复活（ADR-001）。同事务保证两步原子。
     */
    @Transactional
    public List<LinkedIdentityView> unbind(long userId, String provider) {
        String normalized = provider == null ? null : provider.toLowerCase(Locale.ROOT);
        List<UserIdentity> current = userIdentityRepository.findByUserId(userId);

        boolean owns = current.stream().anyMatch(i -> i.provider().equals(normalized));
        if (!owns) {
            throw new IllegalArgumentException("未绑定该登录方式: " + provider);
        }
        if (current.size() <= 1) {
            throw new IllegalStateException("这是你唯一的登录方式，不能解绑");
        }

        userIdentityRepository.deleteByUserIdAndProvider(userId, normalized);
        if ("github".equals(normalized)) {
            userAccountRepository.clearGithubId(userId);
        }
        return listForUser(userId);
    }
}
