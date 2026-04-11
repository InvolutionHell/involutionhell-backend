package com.involutionhell.backend.common.config;

import cn.dev33.satoken.stp.StpInterface;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限与角色加载实现。
 *
 * 项目原来缺少 StpInterface 实现，Sa-Token 找不到实现 Bean 时会回退到默认的空列表，
 * 导致所有 @SaCheckPermission / @SaCheckRole 注解永远校验失败（403），
 * 不管数据库里给用户配了什么权限都没用。这是个生产 Bug，加上这个类才算把权限体系真正接通。
 *
 * 关于 loginId 类型：Sa-Token 内部把登录 ID 序列化成 String 存储，
 * 即使调用 StpUtil.login(Long) 传入的是 Long，回调这里时运行时类型也是 String，
 * 所以不能直接强转，要先 toString() 再 Long.valueOf()。
 */
@Component
public class SaTokenPermissionImpl implements StpInterface {

    private final UserAccountRepository userAccountRepository;

    public SaTokenPermissionImpl(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * 返回用户拥有的权限码列表，Sa-Token 执行 @SaCheckPermission 时会调用此方法。
     *
     * @param loginId   登录 ID，运行时实际类型是 String，不是 Long
     * @param loginType 登录类型，单端场景下为 "login"，此处忽略
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // Sa-Token 回传的 loginId 是 String，必须先 toString() 再转 Long
        return userAccountRepository.findById(Long.valueOf(loginId.toString()))
                .map(account -> List.copyOf(account.permissions()))
                .orElse(List.of());
    }

    /**
     * 返回用户拥有的角色列表，供 @SaCheckRole 使用，逻辑和 getPermissionList 对称。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return userAccountRepository.findById(Long.valueOf(loginId.toString()))
                .map(account -> List.copyOf(account.roles()))
                .orElse(List.of());
    }
}
