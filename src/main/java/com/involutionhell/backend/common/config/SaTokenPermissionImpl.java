package com.involutionhell.backend.common.config;

import cn.dev33.satoken.stp.StpInterface;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限与角色加载实现。
 *
 * <h3>为什么需要这个类？</h3>
 * <p>Sa-Token 的 {@code @SaCheckPermission} / {@code @SaCheckRole} 注解在执行权限校验时，
 * 会调用 {@link StpInterface#getPermissionList} / {@link StpInterface#getRoleList}
 * 来获取当前登录用户的权限集合与角色集合。</p>
 *
 * <p>在此类被添加之前，项目中缺少 {@code StpInterface} 的实现 Bean，
 * Sa-Token 回退使用默认的空列表实现，导致所有 {@code @SaCheckPermission} 检查
 * 无论用户实际持有什么权限，一律抛出 {@code NotPermissionException}（HTTP 403）。
 * 这是一个生产代码 Bug：权限体系在数据库层面已设计完备，但因缺少加载桥梁而完全失效。</p>
 *
 * <h3>实现逻辑</h3>
 * <p>以登录时写入 Sa-Token Session 的用户 ID 为键，从 {@code user_accounts} 表加载
 * {@code permissions} 与 {@code roles} 列（逗号分隔字符串已由 Repository 层解析为 Set）。</p>
 *
 * <h3>loginId 类型说明</h3>
 * <p>Sa-Token 在内部将登录 ID 序列化为 {@code String} 存储，因此
 * {@code loginId} 参数的运行时类型是 {@code String}，而非调用 {@code StpUtil.login(Long)}
 * 时传入的 {@code Long}。故此处需要通过 {@code Long.valueOf(loginId.toString())} 转换。</p>
 */
@Component
public class SaTokenPermissionImpl implements StpInterface {

    private final UserAccountRepository userAccountRepository;

    public SaTokenPermissionImpl(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * 返回指定用户拥有的权限码列表。
     *
     * <p>Sa-Token 每次执行 {@code @SaCheckPermission("user:xxx")} 时都会调用此方法，
     * 将返回值与注解中声明的权限码对比，若不包含则抛出 {@code NotPermissionException}。</p>
     *
     * @param loginId   登录 ID，运行时实际类型为 String（Sa-Token 内部序列化结果）
     * @param loginType 登录类型，单端场景下为 "login"，此处忽略
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // loginId 由 Sa-Token 以 String 形式回传，需先 toString() 再解析为 Long
        return userAccountRepository.findById(Long.valueOf(loginId.toString()))
                .map(account -> List.copyOf(account.permissions()))
                .orElse(List.of());
    }

    /**
     * 返回指定用户拥有的角色标识列表，供 {@code @SaCheckRole} 使用。
     * 逻辑与 {@link #getPermissionList} 完全对称，仅字段来源不同。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return userAccountRepository.findById(Long.valueOf(loginId.toString()))
                .map(account -> List.copyOf(account.roles()))
                .orElse(List.of());
    }
}
