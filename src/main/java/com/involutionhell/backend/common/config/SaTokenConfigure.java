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
                // Events 公开读接口：/api/events 列表 + /api/events/{id} 详情匿名可访问。
                // /api/events/{id}/interest 感兴趣接口需要登录，由 @SaCheckLogin 在方法级别兜底。
                // /api/admin/events/** 不放行，走 @SaCheckRole("admin") 校验。
                .notMatch("/api/events", "/api/events/*")
                .notMatch("/api/chat/sessions/save")       // AI 对话持久化（匿名 / 登录都写，登录时自动关联 userId）
                .check(r -> StpUtil.checkLogin());         // 未登录抛出 NotLoginException
        })).addPathPatterns("/**");
    }
}