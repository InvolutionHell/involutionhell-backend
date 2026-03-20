package com.involutionhell.backend.usercenter.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.UserAuthorizationUpdateRequest;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user-center")
public class UserCenterController {

    private final UserCenterService userCenterService;

    /**
     * 创建用户中心控制器并注入用户服务。
     */
    public UserCenterController(UserCenterService userCenterService) {
        this.userCenterService = userCenterService;
    }

    /**
     * 查询当前登录用户的用户中心资料。
     */
    @PreAuthorize("hasAuthority('user:profile:read')")
    @GetMapping("/profile")
    public ApiResponse<UserView> currentProfile() {
        return ApiResponse.ok(userCenterService.currentUser());
    }

    /**
     * 查询用户中心中的全部用户。
     */
    @PreAuthorize("hasAuthority('user:center:read')")
    @GetMapping("/users")
    public ApiResponse<List<UserView>> listUsers() {
        return ApiResponse.ok(userCenterService.listUsers());
    }

    /**
     * 按用户 ID 查询单个用户详情。
     */
    @PreAuthorize("hasAuthority('user:center:read')")
    @GetMapping("/users/{userId}")
    public ApiResponse<UserView> getUser(@PathVariable Long userId) {
        return ApiResponse.ok(userCenterService.getUser(userId));
    }

    /**
     * 更新指定用户的角色与权限集合。
     */
    @PreAuthorize("hasAuthority('user:center:manage')")
    @PutMapping("/users/{userId}/authorization")
    public ApiResponse<UserView> updateAuthorization(
            @PathVariable Long userId,
            @Valid @RequestBody UserAuthorizationUpdateRequest request
    ) {
        return ApiResponse.ok("权限更新成功", userCenterService.updateAuthorization(userId, request));
    }
}
