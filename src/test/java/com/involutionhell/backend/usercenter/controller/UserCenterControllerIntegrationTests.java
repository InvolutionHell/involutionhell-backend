package com.involutionhell.backend.usercenter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/**
 * UserCenterController + AuthController（/auth/me）集成测试。
 *
 * <h3>URL 路径修正（旧：/api/user-center/* → 新：/users/* 和 /auth/me）</h3>
 * <p>旧版测试使用 {@code /api/user-center/profile}、{@code /api/user-center/users} 等路径，
 * 这些路径在重构中被移除（注释"context-path 已含 /api/v1，此处不再重复加 /api 前缀"说明
 * 服务曾有全局 context-path，后来去掉了）。当前实际映射为：
 * <ul>
 *   <li>当前用户信息 → {@code GET /auth/me}（AuthController）</li>
 *   <li>用户列表      → {@code GET /users}（UserCenterController）</li>
 *   <li>单个用户      → {@code GET /users/{id}}（UserCenterController）</li>
 *   <li>更新权限      → {@code PUT /users/{id}/authorization}（UserCenterController）</li>
 * </ul>
 * 旧路径不存在时，Spring 抛出 {@code NoResourceFoundException}，被 GlobalExceptionHandler
 * 的 {@code handleUnexpected} 兜底捕获，返回 HTTP 500，导致测试全部失败。</p>
 *
 * <h3>权限错误消息修正（旧："无权限访问:..." → 新："拒绝访问: 缺少权限 [...]"）</h3>
 * <p>旧版断言使用的消息与 GlobalExceptionHandler 实际输出不符。
 * 现在 GlobalExceptionHandler 输出 {@code "拒绝访问: 缺少权限 [<权限码>]"}，
 * 测试消息已与之对齐。</p>
 *
 * <h3>@SaCheckPermission 之前为何一直 403？</h3>
 * <p>项目缺少 {@code StpInterface} 实现，Sa-Token 回退使用空列表，
 * 导致所有权限校验恒定失败。已通过新增 {@code SaTokenPermissionImpl} 解决。</p>
 */
class UserCenterControllerIntegrationTests extends AbstractWebIntegrationTest {

    /**
     * 当前用户信息接口现在位于 AuthController（/auth/me），
     * 旧测试错误地访问了已不存在的 /api/user-center/profile。
     */
    @Test
    void profileReturnsCurrentUserForAuthorizedUser() throws Exception {
        String token = loginAsAlice();

        mockMvc.perform(get("/auth/me").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    /**
     * 管理员拥有 user:center:read 权限，可访问全量用户列表。
     * 旧 URL /api/user-center/users 不存在，已修正为 /users。
     */
    @Test
    void usersListReturnsAllUsersForAdmin() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(get("/users").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    /**
     * alice 仅有 user:profile:read，缺少 user:center:read，访问 /users 时 Sa-Token
     * 抛出 NotPermissionException，GlobalExceptionHandler 返回 "拒绝访问: 缺少权限 [user:center:read]"。
     * 旧测试期望的 "无权限访问: user:center:read" 是当时不同的错误消息格式，已对齐。
     */
    @Test
    void usersListRejectsUserWithoutReadPermission() throws Exception {
        String token = loginAsAlice();

        mockMvc.perform(get("/users").header("satoken", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("拒绝访问: 缺少权限 [user:center:read]"));
    }

    /**
     * 审计员拥有 user:profile:read，可查询单个用户详情。
     * 旧 URL /api/user-center/users/2 已修正为 /users/2。
     */
    @Test
    void getUserReturnsRequestedUserForAuditor() throws Exception {
        String token = loginAsAuditor();

        mockMvc.perform(get("/users/2").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    /** 匿名访问 /users/1 触发 NOT_TOKEN，返回 401。 */
    @Test
    void getUserRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/users/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 用户不存在时，UserCenterService 抛出 IllegalArgumentException，
     * GlobalExceptionHandler 将其映射为 400 BAD_REQUEST。
     */
    @Test
    void getUserReturnsBusinessErrorWhenUserMissing() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(get("/users/999").header("satoken", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("用户不存在: 999"));
    }

    /**
     * 管理员成功更新 alice（id=2）的角色与权限后，再次查询验证持久化结果。
     *
     * <p>{@code @DirtiesContext} 在方法执行后重置 Spring 上下文（含 H2 数据库），
     * 防止本测试对 alice 的修改影响同一进程内后续测试的预期数据。</p>
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void updateAuthorizationAllowsAdmin() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(put("/users/2/authorization")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["editor", " reviewer "],
                                  "permissions": ["user:profile:read", "user:center:read"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("权限更新成功"))
                .andExpect(jsonPath("$.data.roles.length()").value(2))
                .andExpect(jsonPath("$.data.permissions.length()").value(2));

        // 二次查询，验证数据库已持久化
        mockMvc.perform(get("/users/2").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(2))
                .andExpect(jsonPath("$.data.permissions.length()").value(2));
    }

    /** 匿名 PUT 请求触发 NOT_TOKEN，返回 401。 */
    @Test
    void updateAuthorizationRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(put("/users/2/authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["user"],
                                  "permissions": ["user:profile:read"]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * alice 缺少 user:center:manage，更新权限时被 @SaCheckPermission 拦截，返回 403。
     * 消息格式已从旧版 "无权限访问: ..." 对齐为 GlobalExceptionHandler 当前输出格式。
     */
    @Test
    void updateAuthorizationRejectsUserWithoutManagePermission() throws Exception {
        String token = loginAsAlice();

        mockMvc.perform(put("/users/2/authorization")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["user"],
                                  "permissions": ["user:profile:read", "user:center:manage"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("拒绝访问: 缺少权限 [user:center:manage]"));
    }
}
