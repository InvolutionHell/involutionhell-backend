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
                .match("/**")    // 拦截所有路由
                .notMatch("/api/auth/login")     // 排除登录接口
                .notMatch("/api/auth/register")  // 排除注册接口      // 排除 Spring Boot 默认错误界面
                .check(r -> StpUtil.checkLogin());  // 校验是否登录，如果未登录，这里会抛出 NotLoginException
        })).addPathPatterns("/**");
    }
}