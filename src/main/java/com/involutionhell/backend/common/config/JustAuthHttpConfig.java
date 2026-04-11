package com.involutionhell.backend.common.config;

import com.xkcoding.http.HttpUtil;
import com.xkcoding.http.support.hutool.HutoolImpl;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * 显式注册 JustAuth 使用的 HTTP 实现。
 *
 * JustAuth 内部的 simple-http 库默认用 Class.forName() 自动探测 HTTP 实现，
 * 在 GraalVM Native Image 中反射受限，探测失败报 "Has no HttpImpl defined in environment!"。
 * 这里手动指定 Hutool 作为 HTTP 实现，绕过反射探测。
 */
@Configuration
public class JustAuthHttpConfig {

    @PostConstruct
    public void init() {
        HttpUtil.setHttp(new HutoolImpl());
    }
}
