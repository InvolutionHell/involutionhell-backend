package com.involutionhell.backend.usercenter.service;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.model.UserIdentity;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import com.involutionhell.backend.usercenter.repository.UserIdentityRepository;
import me.zhyd.oauth.model.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserCenterService userCenterService;
    private final PasswordService passwordService;
    private final UserAccountRepository userAccountRepository;
    private final UserIdentityRepository userIdentityRepository;

    /**
     * 创建认证服务并注入用户与密码服务。
     */
    public AuthService(UserCenterService userCenterService,
                       PasswordService passwordService,
                       UserAccountRepository userAccountRepository,
                       UserIdentityRepository userIdentityRepository) {
        this.userCenterService = userCenterService;
        this.passwordService = passwordService;
        this.userAccountRepository = userAccountRepository;
        this.userIdentityRepository = userIdentityRepository;
    }

    /**
     * 校验登录请求 (传统账号密码登录)。
     *
     * INV-003 lazy upgrade：登录成功且原 hash 是 legacy（裸 SHA-256）格式时，
     * 就地把 hash 升级为 bcrypt。失败不阻断登录——升级失败属可观测事件，
     * 下次登录还会再试，不能让 DB 抖动把用户锁出去。
     */
    public LoginResponse login(LoginRequest request) {
        UserAccount userAccount = userCenterService.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!userAccount.enabled()) {
            throw new IllegalStateException("账号已被禁用");
        }
        if (!passwordService.matches(request.password(), userAccount.passwordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // INV-003 lazy upgrade：把老 SHA-256 hash 升级为 bcrypt（用同一明文重新 hash）
        if (passwordService.isLegacyHash(userAccount.passwordHash())) {
            try {
                userAccountRepository.updatePasswordHash(
                        userAccount.id(),
                        passwordService.hash(request.password()));
                log.info("已就地升级用户 {} 的密码哈希（legacy → bcrypt）", userAccount.username());
            } catch (Exception e) {
                // lazy upgrade 失败不阻断登录——记日志即可，下次登录会再次尝试。
                // 必须把异常对象作为最后一个参数传给 SLF4J 才能打完整堆栈，
                // 仅传 e.getMessage() 会丢失死锁/连接池/权限等根因排查线索。
                log.warn("用户 {} 密码哈希升级失败", userAccount.username(), e);
            }
        }

        return executeLogin(userAccount);
    }
    
    /**
     * GitHub 授权登录（薄委托）。历史入口，保留供 OAuthController 调用。
     */
    public LoginResponse loginByGithub(AuthUser githubUser) {
        return loginByProvider("github", githubUser);
    }

    /**
     * 第三方 provider 授权登录（M1 统一流程）。
     * 不存在则自动注册，已存在则刷新资料；无论哪条路径都维护一行 user_identities。
     *
     * 双写期语义（ADR-001，M1-M3）：账号仍按 "{provider}_{providerUserId}" 用户名主查，
     * user_identities 作为并行写入的第二真相源（M3 才翻转成主查）。github 的 github_id
     * 列同样双写（createUser/updateProfile 已写）。identity 缺失时惰性补齐（自愈），
     * 兜住 M0-M1 窗口内注册、回填尚未覆盖的账号。
     */
    public LoginResponse loginByProvider(String provider, AuthUser authUser) {
        String providerUserId = authUser.getUuid();
        // 保留 "{provider}_{id}" 用户名约定（github 即 "github_{id}"，与历史一致）。
        String username = provider + "_" + providerUserId;

        String displayName = authUser.getNickname() != null ? authUser.getNickname() : authUser.getUsername();
        String avatarUrl   = authUser.getAvatar();
        String email       = authUser.getEmail();
        // github 的 uuid 就是数字用户 ID；非数字（极罕见）时置 null。
        // 非 github provider 不写 github_id 列。
        Long parsedGithubId = null;
        if ("github".equals(provider)) {
            try {
                parsedGithubId = Long.parseLong(providerUserId);
            } catch (NumberFormatException e) {
                parsedGithubId = null;
            }
        }
        final Long githubId = parsedGithubId;

        UserAccount userAccount = userCenterService.findByUsername(username).map(existing ->
            userCenterService.updateProfile(existing.id(), displayName, avatarUrl, email, githubId)
        ).orElseGet(() ->
            autoLinkByVerifiedEmailOrCreate(provider, authUser, username, displayName, avatarUrl, email, githubId)
        );

        if (!userAccount.enabled()) {
            throw new IllegalStateException("账号已被禁用");
        }

        ensureIdentity(userAccount.id(), provider, providerUserId, email, displayName);

        return executeLogin(userAccount);
    }

    /**
     * 该 provider 首次登录、账号还不存在时：
     *   1. provider 的**已验证**邮箱唯一匹配到某个已有账号 → 挂靠到那个账号（不建新号），
     *      防止已有 GitHub 账号的人用 Discord 登录被分叉出第二个账号；
     *   2. 否则（邮箱缺失 / 未验证 / 无匹配 / 多个匹配）→ 建新账号。
     *
     * 安全前提：只信"provider 已验证"的邮箱。攻击者拿受害者邮箱注册的第三方号无法把
     * 邮箱标成 verified（要点验证链接=控制邮箱），所以自动关联不增加攻击面（ADR-001）。
     * 挂靠时**不覆盖**已有账号的展示名/头像/github_id——只由 ensureIdentity 挂上新身份。
     *
     * ponytail: 这是"信任 provider verified 邮箱"的即时防分叉；用户自有 OTP 的规范邮箱
     * 体系（注册时验证）是后续独立子系统，与本逻辑兼容。
     */
    private UserAccount autoLinkByVerifiedEmailOrCreate(String provider, AuthUser authUser, String username,
                                                        String displayName, String avatarUrl,
                                                        String email, Long githubId) {
        if (email != null && !email.isBlank() && isProviderEmailVerified(provider, authUser)) {
            java.util.List<UserAccount> matches = userAccountRepository.findByEmail(email);
            if (matches.size() == 1) {
                UserAccount target = matches.get(0);
                log.info("verified-email 自动关联：provider={} 的已验证邮箱匹配到已有账号 id={}，挂靠不建新号",
                        provider, target.id());
                // 反向场景（先 Discord 注册、后 GitHub 登录挂靠）：目标账号 github_id 可能为空。
                // M1-M3 双写期贡献归属 / /u/{githubId} 都靠这列，补上（仅当为空，不覆盖）。
                // 写失败不阻断登录：撞 UNIQUE(github_id) 等极端情况记日志即可。
                if ("github".equals(provider) && githubId != null && target.githubId() == null) {
                    try {
                        userAccountRepository.setGithubIdIfAbsent(target.id(), githubId);
                    } catch (Exception e) {
                        log.warn("挂靠时补写 github_id 失败（userId={}）", target.id(), e);
                    }
                }
                return target;
            }
            if (matches.size() > 1) {
                // 多账号共用同一邮箱（不应发生）：保守建新号，绝不猜挂给谁
                log.warn("verified-email 命中多个账号（{} 个），保守建新号避免挂错", matches.size());
            }
        }
        UserAccount newUser = new UserAccount(
                null,
                username,
                // 第三方用户不用密码登录，塞随机超长密码占位（password_hash NOT NULL）
                passwordService.hash(UUID.randomUUID().toString()),
                displayName,
                true,
                Set.of("user"),
                Set.of(),
                avatarUrl,
                email,
                githubId,
                null
        );
        return userCenterService.createUser(newUser);
    }

    /**
     * provider 是否已验证该邮箱。只有 true 才允许按邮箱自动关联。
     *   - discord：/users/@me 的 "verified" 布尔（Discord 已验证账号邮箱）
     *   - github：JustAuth 取的是 primary email，GitHub 要求 primary 已验证，信任
     *   - 其它未知 provider：保守返回 false，不自动关联
     */
    private boolean isProviderEmailVerified(String provider, AuthUser authUser) {
        return switch (provider) {
            case "discord" -> {
                com.alibaba.fastjson.JSONObject raw = authUser.getRawUserInfo();
                yield raw != null && raw.getBooleanValue("verified");
            }
            case "github" -> true;
            default -> false;
        };
    }

    /**
     * 该第三方身份是否已经绑过账号。灰度闸用它区分"回访登录"与"建新号"——
     * 只有后者才是灰度要拦的对象。查询失败保守当作不存在（宁可多拦一次，
     * 也不能让一次 DB 抖动把闸变成放行）。
     */
    public boolean hasIdentity(String provider, String providerUserId) {
        try {
            return userIdentityRepository
                    .findByProviderAndProviderUserId(provider, providerUserId)
                    .isPresent();
        } catch (Exception e) {
            log.warn("查询 identity 失败（provider={}），保守视为不存在", provider, e);
            return false;
        }
    }

    /**
     * 维护 user_identities 双写：缺行则插入（惰性自愈），有则刷新 last_login_at。
     * 写失败不阻断登录——与 INV-003 lazy upgrade 同策略，记日志后继续，
     * 下次登录还会再试，绝不让 identity 写入把用户挡在门外。
     */
    private void ensureIdentity(long userId, String provider, String providerUserId,
                                String email, String displayName) {
        try {
            userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)
                    .ifPresentOrElse(
                            existing -> userIdentityRepository.touchLastLogin(existing.id()),
                            () -> userIdentityRepository.insert(new UserIdentity(
                                    null, userId, provider, providerUserId, email, displayName, null, null)));
        } catch (Exception e) {
            log.warn("user_identities 双写失败（provider={} userId={}），不阻断登录", provider, userId, e);
        }
    }
    
    /**
     * 执行底层 Sa-Token 登录操作并封装返回结果。
     */
    private LoginResponse executeLogin(UserAccount userAccount) {
        // 使用 Sa-Token 建立会话
        StpUtil.login(userAccount.id());

        // 返回包含 Token 信息的响应
        return new LoginResponse(
            StpUtil.getTokenName(), 
            StpUtil.getTokenValue(), 
            UserView.from(userAccount)
        );
    }

    /**
     * 退出当前登录会话。
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 返回当前登录用户视图。
     */
    public UserView currentUser() {
        return userCenterService.currentUser();
    }
}