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
 * 旧测试断言的匿名请求错误消息是 "未登录或登录状态已失效"，这是早期 GlobalExceptionHandler 的通用文案。
 * 现在 GlobalExceptionHandler 对 NotLoginException 按场景值细分：
 * 完全没带 token 是 NOT_TOKEN 场景，对应 "未提供 Token"；
 * token 格式非法是 INVALID_TOKEN，对应 "Token 无效"；以此类推。
 * 匿名请求属于 NOT_TOKEN，所以正确消息是 "未提供 Token"。
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

    // 未带 token 是 NOT_TOKEN 场景，GlobalExceptionHandler 返回 "未提供 Token"
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

    // 同上，匿名 logout 也是 NOT_TOKEN 场景
    @Test
    void logoutRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未提供 Token"));
    }
}
