package com.involutionhell.backend.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.involutionhell.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员基础设施（非业务）辅助接口。
 *
 * 设计初衷：给 Caddy `forward_auth` 当鉴权目标，用最小代价把"这个请求是不是
 * admin"这个判断委托给后端 sa-token。所有需要 admin-only 访问的管理工具
 * （pgAdmin、未来的 Umami admin 面板等）共用同一个端点，不再一个工具一个
 * controller——凭据和角色都一样，复用即可。
 *
 * 设计要点：
 *   - sa-token 默认从 header / cookie 两边读 token（sa-token.is-read-cookie=true 默认开）
 *     配合前端在登录时把 satoken 同步写一份到 .involutionhell.com 域名 cookie，
 *     浏览器直接访问 api 子域时也能带上，forward_auth 校验链才能成立
 *   - 响应体故意空壳，Caddy 只看状态码不看 body；保持最小负载
 *   - superadmin 的 roles 集合也包含 "admin"（由 sa-token 角色体系保证），
 *     所以超管也能直接过，不用单独处理
 *   - Infisical **不**走这个端点——它自己有完整的 GitHub OAuth + 内部 RBAC + 审计，
 *     面向所有协作者（非 admin 也能登录，权限在 Infisical 内部按 project 细分）。
 *     Caddy 对 secrets.involutionhell.com 是直通反代，没有 forward_auth。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminInfraController {

    /**
     * Caddy forward_auth 目标。通过 @SaCheckRole("admin") 就 200，否则 sa-token 抛
     * NotLogin / NotPermission 异常，全局异常处理器转成 401 / 403，Caddy 据此拒绝代理。
     *
     * 泛化自原来的 /pgadmin-check：现在 pgAdmin + Umami（以及未来的 admin-only 管理面板）
     * 都指向这一个端点。
     */
    @GetMapping("/devtool-check")
    @SaCheckRole("admin")
    public ApiResponse<Void> devtoolCheck() {
        return ApiResponse.okMessage("authorized");
    }

    /**
     * 向后兼容：保留原 /pgadmin-check 路径，Caddyfile 里暂时还指着它，迁移完成后删除。
     * 两个方法都走同一 @SaCheckRole 语义，不会分叉行为。
     */
    @GetMapping("/pgadmin-check")
    @SaCheckRole("admin")
    public ApiResponse<Void> pgadminCheck() {
        return ApiResponse.okMessage("authorized");
    }
}
