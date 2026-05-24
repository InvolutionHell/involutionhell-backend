package com.involutionhell.backend.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {
    
    // 注册 SaToken 拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 SaToken 拦截器，定义详细认证规则
        registry.addInterceptor(new SaInterceptor(handler -> {
            // 拦截规则配置
            SaRouter
                .match("/**")                              // 拦截所有路由
                .notMatch("/auth/login")                   // 账号密码登录
                .notMatch("/auth/register")                // 注册
                .notMatch("/oauth/render/github")          // GitHub OAuth 授权发起
                .notMatch("/api/auth/callback/github")     // GitHub OAuth 回调（路径与 OAuth App 注册保持一致）
                .notMatch("/analytics/top-docs")           // 文档热榜公开接口
                .notMatch("/analytics/events/summary")     // 事件聚合摘要，公开只读接口
                .notMatch("/analytics/events")             // 浏览器埋点写入，匿名也放行（登录用户通过 satoken header 识别）
                .notMatch("/api/user-center/profile/**")   // 个人主页公开读接口，匿名可访问
                .notMatch("/api/user-center/follows/stats/**")        // 粉丝/关注数公开读
                .notMatch("/api/user-center/follows/followers/**")    // 粉丝列表公开读
                .notMatch("/api/user-center/follows/following/**")    // 关注列表公开读
                .notMatch("/api/user-center/follows/is-following/**") // 匿名查询时返回 false
                .notMatch("/api/user-center/github/repos/**") // GitHub 公开 repos 代理，匿名可访问
                .notMatch("/api/user-center/zotero/items")    // Zotero itemKey 元信息代理，匿名可访问
                .notMatch("/api/docs/history")             // 文档修改历史公开读，匿名可访问
                // /api/public/** 是新约定：完全公开、build-time 可调的接口都挂这里，
                // 通配放行避免每加一个公开接口都要改白名单。当前只有 /api/public/leaderboard。
                //
                // ⚠️ 重要约束：/api/public/** 下严禁挂带副作用的写接口（POST/PUT/PATCH/DELETE）。
                // 这里 notMatch 是按路径放行所有 method，新增 controller 时一律用 @GetMapping。
                // 如果未来需要写接口，必须挪出 /api/public 前缀，否则匿名可调用 = 安全洞。
                .notMatch("/api/public/**")
                // Events 公开读接口：/api/events 列表 + /api/events/{id} 详情匿名可访问。
                // /api/events/{id}/interest 感兴趣接口需要登录，由 @SaCheckLogin 在方法级别兜底。
                // /api/admin/events/** 不放行，走 @SaCheckRole("admin") 校验。
                .notMatch("/api/events", "/api/events/*")
                // Community 公开读：GET /api/community/links 列表匿名可访问。
                // POST 提交 / 举报 / GET /mine 走方法级 @SaCheckLogin 校验。
                // /api/admin/community/** 不放行，走 @SaCheckRole("admin") 校验。
                .notMatch("/api/community/links")
                // 机器人桥接渠道（Discord ChatBot 等）：走 X-Internal-Key header 认证，
                // 不要求 sa-token 登录态。Controller 自己校验密钥。
                // 用 /** 覆盖子路径（/internal 提交 + /internal/summary 查询）。
                .notMatch("/api/community/links/internal", "/api/community/links/internal/**")
                .notMatch("/api/chat/sessions/save")       // AI 对话持久化（匿名 / 登录都写，登录时自动关联 userId）
                // Posts 公开读接口：
                //   GET /api/posts/feed                  - /feed 原创 Tab 列表（匿名可访问）
                //   GET /api/posts/{username}/{slug}     - 详情/分享页（匿名可访问）
                // 写接口（POST/PUT/DELETE）和 /mine 由方法级 @SaCheckLogin 守卫，无需在此放行。
                .notMatch("/api/posts/feed")
                .notMatch("/api/posts/*/*")
                // 文档路径解析：GET /api/docs/resolve?path=... 公开，无需登录
                .notMatch("/api/docs/resolve")
                .check(r -> StpUtil.checkLogin());         // 未登录抛出 NotLoginException
        })).addPathPatterns("/**");
    }
}