package com.involutionhell.backend.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.involutionhell.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员基础设施（非业务）辅助接口。
 *
 * 目前只有一个：/api/admin/pgadmin-check —— 专门给 Caddy `forward_auth` 调用，
 * 用来判断当前请求是否是 admin。通过就 200，否则 sa-token 自动抛 NotLogin /
 * NotPermission 异常，全局异常处理器转成 401 / 403，Caddy 据此拒绝代理到 pgAdmin。
 *
 * 设计要点：
 *   - sa-token 默认从 header / cookie 两边读 token（sa-token.is-read-cookie=true 默认开）
 *     配合前端在登录时把 satoken 同步写一份到 .involutionhell.com 域名 cookie，
 *     浏览器直接访问 api 子域时也能带上，forward_auth 校验链才能成立
 *   - 响应体故意空壳，Caddy 只看状态码不看 body；保持最小负载
 *   - 单独放在 admin/controller 包下而不是塞进 events/controller：这是
 *     "基础设施级"鉴权桩，不属于任何业务域，放一起语义会误导
 */
@RestController
@RequestMapping("/api/admin")
public class AdminInfraController {

    /**
     * Caddy 的 forward_auth 目标。只要通过 @SaCheckRole("admin") 就返回 200。
     *
     * superadmin 的 roles 集合也包含 "admin"（由 sa-token 角色体系保证），
     * 所以超管也能直接过，不用单独处理。
     */
    @GetMapping("/pgadmin-check")
    @SaCheckRole("admin")
    public ApiResponse<Void> pgadminCheck() {
        return ApiResponse.okMessage("authorized");
    }
}
