package com.involutionhell.backend.usercenter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * AuthController 集成测试（账号密码登录、退出、当前用户查询）。
 *
 * <h3>历史测试失败原因（已修复）</h3>
 * <p>修复前，所有测试都报 "Driver claims to not accept jdbcUrl, jdbc:postgresql://..."，
 * 根本原因是 {@code SPRING_DATASOURCE_URL} 环境变量覆盖了 {@code application-test.properties}
 * 中的 H2 配置，已通过 {@link com.involutionhell.backend.support.AbstractWebIntegrationTest}
 * 的 {@code @SpringBootTest(properties)} 解决。</p>
 *
 * <h3>匿名请求错误消息的变化（"未登录..." → "未提供 Token"）</h3>
 * <p>旧版测试断言 {@code "未登录或登录状态已失效"}，此消息来自早期 GlobalExceptionHandler
 * 使用通用文案的版本。当前 GlobalExceptionHandler 对 {@code NotLoginException} 按场景值细分：
 * <ul>
 *   <li>{@code NOT_TOKEN}（完全未携带 token）→ "未提供 Token"</li>
 *   <li>{@code INVALID_TOKEN}（token 格式非法）→ "Token 无效"</li>
 *   <li>{@code TOKEN_TIMEOUT} → "Token 已过期"</li>
 *   <li>…</li>
 * </ul>
 * 匿名请求属于 {@code NOT_TOKEN} 场景，故正确消息为 "未提供 Token"。</p>
 */
class AuthControllerIntegrationTests extends AbstractWebIntegrationTest {

    @Test
    void loginReturnsTokenAndCurrentUserInfo() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "Admin@123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("登录成功"))
                .andExpect(jsonPath("$.data.tokenName").value("satoken"))
                .andExpect(jsonPath("$.data.tokenValue").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value("admin"));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void loginValidatesBlankUsername() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "",
                                  "password": "Admin@123456"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("username: 用户名不能为空"));
    }

    @Test
    void meReturnsCurrentUserWhenLoggedIn() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(get("/auth/me").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.permissions[0]").isNotEmpty());
    }

    /**
     * 未携带任何 token 访问受保护接口，Sa-Token 抛出 NOT_TOKEN 场景的 NotLoginException，
     * GlobalExceptionHandler 将其映射为 "未提供 Token"（而非旧版通用文案"未登录或登录状态已失效"）。
     */
    @Test
    void meRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未提供 Token"));
    }

    @Test
    void logoutSucceedsAndMakesTokenInvalid() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(post("/auth/logout").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("退出成功"));

        // 退出后原 token 应失效，再次访问 /me 返回 401
        mockMvc.perform(get("/auth/me").header("satoken", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 匿名 POST /auth/logout，同样属于 NOT_TOKEN 场景，期望 "未提供 Token"。
     * 旧版测试使用通用消息，已更新为当前 GlobalExceptionHandler 的实际输出。
     */
    @Test
    void logoutRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未提供 Token"));
    }
}
