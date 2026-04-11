package com.involutionhell.backend.usercenter.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.UserAuthorizationUpdateRequest;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users")  // context-path 已含 /api/v1，此处不再重复加 /api 前缀
public class UserCenterController {

    private final UserCenterService userCenterService;

    /**
     * 创建用户中心控制器并注入服务。
     */
    public UserCenterController(UserCenterService userCenterService) {
        this.userCenterService = userCenterService;
    }

    /**
     * 查询系统内所有用户，通常用于管理后台。
     */
    @SaCheckPermission("user:center:read")
    @GetMapping
    public ApiResponse<List<UserView>> listUsers() {
        return ApiResponse.ok(userCenterService.listUsers());
    }

    /**
     * 获取指定用户的详细信息。
     */
    @SaCheckPermission("user:profile:read")
    @GetMapping("/{userId}")
    public ApiResponse<UserView> getUser(@PathVariable Long userId) {
        return ApiResponse.ok(userCenterService.getUser(userId));
    }

    /**
     * 更新指定用户的角色与权限。
     */
    @SaCheckPermission("user:center:manage")
    @PutMapping("/{userId}/authorization")
    public ApiResponse<UserView> updateAuthorization(
            @PathVariable Long userId,
            @Valid @RequestBody UserAuthorizationUpdateRequest request) {
        return ApiResponse.ok(
                "权限更新成功",
                userCenterService.updateAuthorization(userId, request)
        );
    }
}