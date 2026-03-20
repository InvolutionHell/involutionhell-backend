package com.involutionhell.backend.usercenter.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.involutionhell.backend.common.nativeimage.UserCenterRuntimeHints;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ImportRuntimeHints(UserCenterRuntimeHints.class)
public class SaTokenConfiguration implements WebMvcConfigurer {

    /**
     * 注册 Sa-Token 拦截器以启用注解鉴权。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor())
                .addPathPatterns("/**");
    }
}
