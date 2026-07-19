package com.involutionhell.backend.usercenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.LinkedIdentityView;
import com.involutionhell.backend.usercenter.service.UserIdentityService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的第三方登录身份管理（M2a：查看 / 解绑）。
 * 路径走 /api/user-center/*，已被前端 next.config rewrite 覆盖。
 * 绑定新 provider（M2b）走 OAuth 流程，不在此。
 */
@RestController
@RequestMapping("/api/user-center/identities")
public class IdentityController {

    private final UserIdentityService userIdentityService;

    public IdentityController(UserIdentityService userIdentityService) {
        this.userIdentityService = userIdentityService;
    }

    /** 列出当前用户已绑定的登录身份。 */
    @SaCheckLogin
    @GetMapping
    public ApiResponse<List<LinkedIdentityView>> list() {
        return ApiResponse.ok(userIdentityService.listForUser(StpUtil.getLoginIdAsLong()));
    }

    /** 解绑指定 provider。返回解绑后剩余身份列表。 */
    @SaCheckLogin
    @DeleteMapping("/{provider}")
    public ApiResponse<List<LinkedIdentityView>> unbind(@PathVariable String provider) {
        return ApiResponse.ok(userIdentityService.unbind(StpUtil.getLoginIdAsLong(), provider));
    }
}
