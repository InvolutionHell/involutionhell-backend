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
                .check(r -> StpUtil.checkLogin());         // 未登录抛出 NotLoginException
        })).addPathPatterns("/**");
    }
}