package com.involutionhell.backend.usercenter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

class UserCenterControllerIntegrationTests extends AbstractWebIntegrationTest {

    @Test
    void profileReturnsCurrentUserForAuthorizedUser() throws Exception {
        String token = loginAsAlice();

        mockMvc.perform(get("/api/user-center/profile").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void usersListReturnsAllUsersForAdmin() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(get("/api/user-center/users").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void usersListRejectsUserWithoutReadPermission() throws Exception {
        String token = loginAsAlice();

        mockMvc.perform(get("/api/user-center/users").header("satoken", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("无权限访问: user:center:read"));
    }

    @Test
    void getUserReturnsRequestedUserForAuditor() throws Exception {
        String token = loginAsAuditor();

        mockMvc.perform(get("/api/user-center/users/2").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value("alice"));
    }

    @Test
    void getUserRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/user-center/users/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getUserReturnsBusinessErrorWhenUserMissing() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(get("/api/user-center/users/999").header("satoken", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("用户不存在: 999"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void updateAuthorizationAllowsAdmin() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(put("/api/user-center/users/2/authorization")
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

        mockMvc.perform(get("/api/user-center/users/2").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles.length()").value(2))
                .andExpect(jsonPath("$.data.permissions.length()").value(2));
    }

    @Test
    void updateAuthorizationRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(put("/api/user-center/users/2/authorization")
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

        mockMvc.perform(put("/api/user-center/users/2/authorization")
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
                .andExpect(jsonPath("$.message").value("无权限访问: user:center:manage"));
    }
}
