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
 * 旧测试用的 URL 是 /api/user-center/profile、/api/user-center/users 等，
 * 这些路径在重构中已经删掉了（服务以前有全局 context-path，后来去掉了，前缀 /api 也跟着没了）。
 * 现在的实际路由是：GET /auth/me、GET /users、GET /users/{id}、PUT /users/{id}/authorization。
 * 旧路径访问时 Spring 抛 NoResourceFoundException，被 GlobalExceptionHandler 兜底返回 500，
 * 所以所有测试都失败了。把 URL 改对就好了。
 *
 * 权限错误消息也变了：旧测试期望 "无权限访问: user:center:read"，
 * 但 GlobalExceptionHandler 实际输出是 "拒绝访问: 缺少权限 [user:center:read]"，对齐即可。
 *
 * @SaCheckPermission 之前一直 403 的原因：项目缺少 StpInterface 实现，
 * Sa-Token 找不到实现 Bean 就用空列表兜底，所有权限校验必然失败。
 * 新增 SaTokenPermissionImpl 后才真正把权限数据接进来。
 */
class UserCenterControllerIntegrationTests extends AbstractWebIntegrationTest {

    // 旧测试访问的 /api/user-center/profile 已不存在，现在是 /auth/me
    @Test
    void profileReturnsCurrentUserForAuthorizedUser() throws Exception {
        String token = loginAsAlice();

        mockMvc.perform(get("/auth/me").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void usersListReturnsAllUsersForAdmin() throws Exception {
        String token = loginAsAdmin();

        // 种子 admin/alice/auditor/discord-bridge 四个（PR #18 起加 discord-bridge）
        mockMvc.perform(get("/users").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(4));
    }

    // alice 只有 user:profile:read，没有 user:center:read，访问 /users 会被拦截
    // 旧测试期望的消息 "无权限访问: ..." 和 GlobalExceptionHandler 实际输出不一致，已修正
    @Test
    void usersListRejectsUserWithoutReadPermission() throws Exception {
        String token = loginAsAlice();

        mockMvc.perform(get("/users").header("satoken", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("拒绝访问: 缺少权限 [user:center:read]"));
    }

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

    // @DirtiesContext 确保本测试对 alice 的修改不会污染其他测试的预期数据
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
