package com.involutionhell.backend.usercenter.oauth;

import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;

/**
 * 一个第三方登录 provider 的完整契约。**接入新 provider 只需新增一个本接口的实现**，
 * 不要再往控制器/服务里加 `switch (provider)` —— 那正是维护漂移的来源：
 * 曾经同一个 provider 名散落在 authRequestFor / isProviderEmailVerified / redirectUriOf
 * 三个 switch 里，漏改一处就得到"能跳转但邮箱不被信任"这种半死状态。
 *
 * 实现类声明成 Spring bean 即可，{@link AuthProviderRegistry} 会自动按 {@link #key()} 收集。
 * 详细接入步骤见 usercenter/README.md。
 */
public interface AuthProvider {

    /** provider 标识，必须小写，与 URL 路径段、user_identities.provider 列一致。 */
    String key();

    /**
     * 构造本次授权用的 JustAuth 请求。
     * 未配置 client-id / secret 时抛 {@link IllegalArgumentException}，
     * 由调用方兜底重定向到 error=oauth_provider（配置问题，不该是 500）。
     */
    AuthRequest newRequest();

    /** 回调地址，仅用于排查日志（公开信息，不含密钥）。 */
    String redirectUri();

    /**
     * 该 provider 是否**已验证**这个邮箱。只有 true 才允许按邮箱自动关联到已有账号，
     * 否则攻击者用一个谎称受害者邮箱的第三方号就能撞进别人账号（ADR-001 红线）。
     * 拿不准就返回 false —— 代价只是多建一个账号，而不是账号被接管。
     */
    boolean isEmailVerified(AuthUser user);

    /**
     * 撤销已换到的 access token。用于"换完 token 才决定拒绝该用户"的场景（灰度闸）。
     * provider 不支持就保持默认空实现。
     */
    default void revokeToken(AuthToken token) {
        // 默认不做事：多数 provider 的 token 会自然过期，撤销只是礼貌性清理
    }
}
