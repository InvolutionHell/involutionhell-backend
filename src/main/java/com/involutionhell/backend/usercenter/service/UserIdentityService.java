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
 * 登录身份的读取、绑定（M2b）与解绑（M2a）。
 * 绑定由 OAuthController 的 /oauth/bind/{provider} 流程走完 OAuth 后调用。
 */
@Service
public class UserIdentityService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserIdentityService.class);

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

    /** 绑定冲突。带一个前端可辨识的 code，便于给出"该账号已被占用"之类的具体文案。 */
    public static class IdentityAlreadyBoundException extends RuntimeException {
        private final String errorCode;

        IdentityAlreadyBoundException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    /**
     * 绑定一个第三方身份到已有账号（M2b）。**不建号、不改会话**——调用方必须已经从
     * 服务端校验过的会话拿到 userId。
     *
     * 两种冲突分别对应 schema 里的两条唯一约束，都要给出可区分的提示而不是 500：
     *   - UNIQUE(provider, provider_user_id)：该第三方身份已绑到别的账号
     *   - UNIQUE(user_id, provider)        ：本账号已经绑过同类 provider
     *
     * github 额外补写 user_accounts.github_id（仅当为空）——贡献归属与 /u/{githubId}
     * 都依赖该列，与 unbind 时清空它是对称的。
     */
    @Transactional
    public List<LinkedIdentityView> bind(long userId, String provider, String providerUserId,
                                         String email, String displayName) {
        String normalized = provider == null ? null : provider.toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank() || providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("provider 与 providerUserId 不能为空");
        }

        userIdentityRepository.findByProviderAndProviderUserId(normalized, providerUserId)
                .ifPresent(existing -> {
                    if (existing.userId() == userId) {
                        throw new IdentityAlreadyBoundException("bind_already_yours",
                                "该登录方式已经绑定在你的账号上了");
                    }
                    throw new IdentityAlreadyBoundException("bind_taken",
                            "该第三方账号已绑定到另一个账号，请先在那个账号里解绑");
                });

        boolean sameProviderBound = userIdentityRepository.findByUserId(userId).stream()
                .anyMatch(i -> i.provider().equals(normalized));
        if (sameProviderBound) {
            throw new IdentityAlreadyBoundException("bind_duplicate",
                    "你已经绑定过 " + normalized + " 了，一个账号同一登录方式只能绑一个");
        }

        userIdentityRepository.insert(new UserIdentity(
                null, userId, normalized, providerUserId, email, displayName, null, null));

        if ("github".equals(normalized)) {
            try {
                userAccountRepository.setGithubIdIfAbsent(userId, Long.parseLong(providerUserId));
            } catch (NumberFormatException e) {
                // github 的 uuid 就是数字用户 ID；非数字（极罕见）时跳过，不阻断绑定
                log.warn("绑定 github 时 providerUserId 非数字，跳过 github_id 回填: userId={}", userId);
            }
        }
        return listForUser(userId);
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
