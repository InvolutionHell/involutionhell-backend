package com.involutionhell.backend.usercenter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/auth/login", "/actuator/**", "/public/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(withDefaults()) // 启用 OAuth2 登录支持
            .oauth2Client(withDefaults()) // 启用 OAuth2 Client 支持
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults())); // 启用 JWT 校验 (Resource Server)

        return http.build();
    }
}
