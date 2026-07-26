package com.involutionhell.backend.usercenter.repository;

import com.involutionhell.backend.usercenter.model.UserIdentity;

import java.util.List;
import java.util.Optional;

/**
 * user_identities 仓库接口。登录流程按 (provider, providerUserId) 定位账号，
 * 设置页按 userId 列出已绑定身份。
 */
public interface UserIdentityRepository {

    Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);

    List<UserIdentity> findByUserId(long userId);

    /**
     * 插入新身份并返回带生成 id 的记录。
     * 撞 UNIQUE（身份已绑他人 / 该账号同 provider 已有身份）由调用方捕获
     * DuplicateKeyException 处理——那是业务分支（提示"已被绑定"），不是异常路径。
     * 传入的 linkedAt / lastLoginAt 会被忽略：linked_at 由 DB DEFAULT NOW() 生成，
     * last_login_at 只经 recordLogin 更新。需要保留历史时间戳的导入场景（若出现）
     * 得加专门方法，不复用本方法。
     */
    UserIdentity insert(UserIdentity identity);

    /**
     * 记录一次登录：刷新 last_login_at，并**补齐**仍为空的 email_at_link /
     * display_name_at_link。
     *
     * 补齐是必要的：M0 回填建出来的身份行只有 (user_id, provider, provider_user_id)，
     * 这两列一直是空的，而设置页靠 display_name_at_link 显示"GitHub · 某某"，
     * 空就只剩一个光秃秃的 provider 名。只在为空时填，不覆盖已有值——列名的
     * at_link 语义是"绑定当时的值"，不该被后来的登录改写。
     */
    void recordLogin(long id, String emailAtLink, String displayNameAtLink);

    /**
     * 删除某账号的某 provider 身份，返回受影响行数（0 = 本无此绑定）。
     */
    int deleteByUserIdAndProvider(long userId, String provider);
}
