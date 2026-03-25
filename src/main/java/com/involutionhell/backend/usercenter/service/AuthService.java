package com.involutionhell.backend.usercenter.service;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import me.zhyd.oauth.model.AuthUser;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private final UserCenterService userCenterService;
    private final PasswordService passwordService;

    /**
     * 创建认证服务并注入用户与密码服务。
     */
    public AuthService(UserCenterService userCenterService, PasswordService passwordService) {
        this.userCenterService = userCenterService;
        this.passwordService = passwordService;
    }

    /**
     * 校验登录请求 (传统账号密码登录)。
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

        return executeLogin(userAccount);
    }
    
    /**
     * 第三方 GitHub 授权登录逻辑。
     * 如果用户不存在，则自动注册；如果已存在，则刷新其头像、邮箱等资料。
     */
    public LoginResponse loginByGithub(AuthUser githubUser) {
        // 使用特殊的 github_ 前缀来标识这是第三方登录的用户，防止与普通用户名冲突
        String githubUsername = "github_" + githubUser.getUuid();

        // 从 JustAuth 提取 GitHub 资料字段
        String displayName = githubUser.getNickname() != null ? githubUser.getNickname() : githubUser.getUsername();
        String avatarUrl   = githubUser.getAvatar();
        String email       = githubUser.getEmail();
        // JustAuth 对 GitHub 的 uuid 就是 GitHub 的数字用户 ID（字符串形式）
        // 用 final 变量包装，确保 lambda 内可以引用（try-catch 双路赋值不是 effectively final）
        Long parsedGithubId;
        try {
            parsedGithubId = Long.parseLong(githubUser.getUuid());
        } catch (NumberFormatException e) {
            parsedGithubId = null;
        }
        final Long githubId = parsedGithubId;

        // 查找是否已经有该用户
        UserAccount userAccount = userCenterService.findByUsername(githubUsername).map(existing -> {
            // 已存在：刷新头像、邮箱、展示名称（GitHub 用户可能更新了自己的资料）
            return userCenterService.updateProfile(existing.id(), displayName, avatarUrl, email, githubId);
        }).orElseGet(() -> {
            // 不存在：自动注册新用户
            UserAccount newUser = new UserAccount(
                    null, // ID 由数据库自动生成
                    githubUsername,
                    // 给第三方用户生成一个随机超长密码，他们不需要用密码登录
                    passwordService.hash(UUID.randomUUID().toString()),
                    displayName,
                    true,           // 默认启用
                    Set.of("user"), // 赋予默认角色（小写，与 normalizeSet 一致）
                    Set.of(),       // 默认权限
                    avatarUrl,
                    email,
                    githubId
            );
            return userCenterService.createUser(newUser);
        });

        // 检查该用户是否已被系统管理员禁用
        if (!userAccount.enabled()) {
            throw new IllegalStateException("账号已被禁用");
        }

        // 执行 Sa-Token 登录并返回信息
        return executeLogin(userAccount);
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