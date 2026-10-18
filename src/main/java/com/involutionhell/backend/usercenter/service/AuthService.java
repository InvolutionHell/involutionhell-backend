package com.involutionhell.backend.usercenter.service;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.PendingRegistration;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.model.UserIdentity;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import com.involutionhell.backend.usercenter.repository.UserIdentityRepository;
import me.zhyd.oauth.model.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserCenterService userCenterService;
    private final PasswordService passwordService;
    private final UserAccountRepository userAccountRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final com.involutionhell.backend.usercenter.oauth.AuthProviderRegistry providers;
    private final RegistrationService registrationService;

    /** 第三方登录的两种结局：登进已有账号，或（新用户）先去验证邮箱。 */
    public sealed interface ProviderLogin permits LoggedIn, NeedsRegistration {}
    public record LoggedIn(LoginResponse response) implements ProviderLogin {}
    public record NeedsRegistration(String pendingId) implements ProviderLogin {}

    /** 验证通过的邮箱已属于某个已有账号。不合并（ADR-001 红线），让用户登录那个账号后去绑定。 */
    public static class EmailTakenException extends RuntimeException {}

    /** 第三方登录命中的账号已被禁用。回调据此给出 account_disabled，而不是笼统的 oauth_failed。 */
    public static class AccountDisabledException extends IllegalStateException {
        AccountDisabledException() {
            super("账号已被禁用");
        }
    }

    /**
     * 创建认证服务并注入用户与密码服务。
     */
    public AuthService(UserCenterService userCenterService,
                       PasswordService passwordService,
                       UserAccountRepository userAccountRepository,
                       UserIdentityRepository userIdentityRepository,
                       com.involutionhell.backend.usercenter.oauth.AuthProviderRegistry providers,
                       RegistrationService registrationService) {
        this.userCenterService = userCenterService;
        this.passwordService = passwordService;
        this.userAccountRepository = userAccountRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.providers = providers;
        this.registrationService = registrationService;
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
     * 第三方 provider 授权登录（M1 统一流程）。只登入已有账号，**从不建号**：
     * 找到账号则补齐资料并维护一行 user_identities；新用户返回 {@link NeedsRegistration}，
     * 验证邮箱后由 {@link #completeRegistration} 建号。
     *
     * 查找顺序（identity-first，ADR-001 迁移表"登录主查翻转"）：
     *   1. user_identities 命中 (provider, providerUserId) → 登入该身份所属账号；
     *   2. 未命中 → 按 "{provider}_{providerUserId}" 用户名查，**仅限一行身份都没有的账号**
     *      （M0 回填前的老账号，见 legacyAccountByUsername）；
     *   3. 仍未命中 → 已验证邮箱自动关联 → 都不中则进 OTP 注册。
     * 必须身份优先：M2b 绑定后，身份所属账号的用户名不再是 "{provider}_{id}"，按用户名查
     * 会 miss → 走邮箱关联（邮箱不同即分叉）或建号（github 撞 UNIQUE(github_id) 直接 500）。
     * identity 缺失时由 ensureIdentity 惰性补齐（自愈）。
     */
    public ProviderLogin loginByProvider(String provider, AuthUser authUser) {
        String providerUserId = authUser.getUuid();
        // 保留 "{provider}_{id}" 用户名约定（github 即 "github_{id}"，与历史一致）。
        String username = provider + "_" + providerUserId;

        String displayName = authUser.getNickname() != null ? authUser.getNickname() : authUser.getUsername();
        String avatarUrl   = authUser.getAvatar();
        String email       = authUser.getEmail();
        // 只有 provider 已验证的邮箱才配写进 user_accounts.email：它是自动挂靠的锚点，
        // 写进未验证邮箱 = 给"谎称别人邮箱"的第三方号留了一个挂靠入口。
        String verifiedEmail = isProviderEmailVerified(provider, authUser) ? email : null;
        Long githubId = null;
        if ("github".equals(provider)) {
            try {
                githubId = Long.parseLong(providerUserId);
            } catch (NumberFormatException e) {
                githubId = null;
            }
        }
        final Long gid = githubId;

        // 身份查询失败直接抛：吞掉再回退到用户名/邮箱路径，一次 DB 抖动就可能分叉出新账号。
        Optional<UserAccount> found = userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .flatMap(identity -> userAccountRepository.findById(identity.userId()))
                .or(() -> legacyAccountByUsername(username))
                // 用这家 provider 建的号（用户名前缀）跟着它刷新展示名/头像；别家登录只补空
                .map(account -> userCenterService.updateProfile(account.id(), displayName, avatarUrl,
                        verifiedEmail, gid, account.username().startsWith(provider + "_")))
                .or(() -> autoLinkByVerifiedEmail(provider, verifiedEmail, gid));

        if (found.isEmpty()) {
            // 新用户：不在这里建号，先验证邮箱（RegistrationService），验证通过由 completeRegistration 建号。
            return new NeedsRegistration(registrationService.begin(new PendingRegistration(
                    provider, providerUserId, displayName, avatarUrl, email, gid)));
        }
        UserAccount userAccount = found.get();
        if (!userAccount.enabled()) {
            throw new AccountDisabledException();
        }

        ensureIdentity(userAccount.id(), provider, providerUserId, email, displayName);

        return new LoggedIn(executeLogin(userAccount));
    }

    /**
     * OTP 验证通过后建号 + 挂身份 + 登入，同一事务（任一步失败都不留半个账号）。
     * 邮箱已属于已有账号时抛 {@link EmailTakenException}，什么都不写。
     * 撞唯一约束（同一身份并发注册）抛 DataIntegrityViolationException，由调用方当作会话失效。
     * ponytail: StpUtil.login 在提交前执行；提交失败时 token 不会返回给任何人，
     * 内存里那条会话指向一个永不复用的序列 id，30 天后自然过期。
     */
    @org.springframework.transaction.annotation.Transactional
    public LoginResponse completeRegistration(RegistrationService.VerifiedRegistration verified) {
        if (!userAccountRepository.findByEmail(verified.verifiedEmail()).isEmpty()) {
            throw new EmailTakenException();
        }
        PendingRegistration p = verified.pending();
        String username = p.provider() + "_" + p.providerUserId();
        if (userCenterService.findByUsername(username).isPresent()) {
            // 被一个已经解绑/换绑掉这个身份的老账号占着（它不再是这个身份的家，见 legacyAccountByUsername）。
            // ponytail: 6 位随机后缀；再撞由 UNIQUE(username) 兜底成 410，重新登录即可。
            username += "_" + UUID.randomUUID().toString().substring(0, 6);
        }
        UserAccount created = userCenterService.createUser(new UserAccount(
                null,
                username,
                // 第三方用户不用密码登录，塞随机超长密码占位（password_hash NOT NULL）
                passwordService.hash(UUID.randomUUID().toString()),
                p.displayName(),
                true,
                Set.of("user"),
                Set.of(),
                p.avatarUrl(),
                verified.verifiedEmail(),
                p.githubId(),
                null
        ));
        userIdentityRepository.insert(new UserIdentity(
                null, created.id(), p.provider(), p.providerUserId(), p.providerEmail(), p.displayName(), null, null));
        return executeLogin(created);
    }

    /**
     * 按 "{provider}_{id}" 用户名找账号，只认**一行身份都没有**的账号——M0 回填之前的老账号。
     * 有身份行的账号按用户名命中，只可能是这个身份已经被解绑或换绑：登进去等于解绑失效，
     * 换绑后的旧 GitHub 还能登录且设置页看不到、撤不掉。那种情况走注册（用户名另取）。
     */
    private Optional<UserAccount> legacyAccountByUsername(String username) {
        return userCenterService.findByUsername(username)
                .filter(account -> userIdentityRepository.findByUserId(account.id()).isEmpty());
    }

    /**
     * 该 provider 首次登录、账号还不存在时：provider 的**已验证**邮箱唯一匹配到某个已有账号 →
     * 挂靠到那个账号（不建新号），防止已有 GitHub 账号的人用 Discord 登录被分叉出第二个账号。
     * 邮箱缺失 / 未验证 / 无匹配 / 多个匹配 → empty，交给 OTP 注册。
     *
     * 安全前提：只信"provider 已验证"的邮箱（调用方已过滤）。攻击者拿受害者邮箱注册的第三方号
     * 无法把邮箱标成 verified，所以自动关联不增加攻击面（ADR-001）。
     * 挂靠时**不覆盖**已有账号的展示名/头像/github_id——只由 ensureIdentity 挂上新身份。
     */
    private Optional<UserAccount> autoLinkByVerifiedEmail(String provider, String verifiedEmail, Long githubId) {
        if (verifiedEmail == null || verifiedEmail.isBlank()) {
            return Optional.empty();
        }
        java.util.List<UserAccount> matches = userAccountRepository.findByEmail(verifiedEmail);
        if (matches.size() > 1) {
            // 多账号共用同一邮箱（不应发生）：绝不猜挂给谁
            log.warn("verified-email 命中多个账号（{} 个），不自动关联", matches.size());
        }
        if (matches.size() != 1) {
            return Optional.empty();
        }
        UserAccount target = matches.get(0);
        // 目标账号已有这家的身份：新身份挂不上去（UNIQUE(user_id, provider)），而"同家第二个号
        // 报了同一个邮箱"也不是同一个人的证据（邮箱回收转手、或锚点本身来路不正）。不挂靠。
        if (userIdentityRepository.findByUserId(target.id()).stream().anyMatch(i -> provider.equals(i.provider()))) {
            log.warn("verified-email 命中的账号 id={} 已有 {} 身份，不自动关联", target.id(), provider);
            return Optional.empty();
        }
        log.info("verified-email 自动关联：provider={} 的已验证邮箱匹配到已有账号 id={}，挂靠不建新号",
                provider, target.id());
        // 反向场景（先 Discord 注册、后 GitHub 登录挂靠）：目标账号 github_id 可能为空。
        // M1-M3 双写期贡献归属 / /u/{githubId} 都靠这列，补上（仅当为空，不覆盖）。
        if ("github".equals(provider) && githubId != null && target.githubId() == null) {
            try {
                userAccountRepository.setGithubIdIfAbsent(target.id(), githubId);
            } catch (Exception e) {
                log.warn("挂靠时补写 github_id 失败（userId={}）", target.id(), e);
            }
        }
        return Optional.of(target);
    }

    /**
     * provider 是否已验证该邮箱。只有 true 才允许按邮箱自动关联。
     * 判定下沉到各 AuthProvider 实现（接新 provider 不用改这里）；
     * 未注册的 provider 保守返回 false，绝不自动关联。
     */
    private boolean isProviderEmailVerified(String provider, AuthUser authUser) {
        return providers.find(provider)
                .map(p -> p.isEmailVerified(authUser))
                .orElse(false);
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
     * 维护 user_identities 双写：缺行则插入（惰性自愈），有行则刷新 last_login_at
     * 并补齐仍为空的 email/display_name——回填建出来的行这两列一直是空的，
     * 只更新时间戳的话设置页永远显示不出账号名。
     * 写失败不阻断登录——与 INV-003 lazy upgrade 同策略，记日志后继续，
     * 下次登录还会再试，绝不让 identity 写入把用户挡在门外。
     * 例外是撞唯一约束：身份注定挂不上这个账号（该账号已有同家身份 / 身份刚被别人占走），
     * 放行就是"身份不在账号上却能登进去"，解绑撤不掉。拒绝这次登录。
     */
    private void ensureIdentity(long userId, String provider, String providerUserId,
                                String email, String displayName) {
        try {
            userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)
                    .ifPresentOrElse(
                            // 顺带补齐回填行留下的空 email/display_name（自愈，见 recordLogin）
                            existing -> userIdentityRepository.recordLogin(existing.id(), email, displayName),
                            () -> userIdentityRepository.insert(new UserIdentity(
                                    null, userId, provider, providerUserId, email, displayName, null, null)));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("该登录方式无法关联到这个账号", e);
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