// Temporarily commented out for JustAuth Migration

// package com.involutionhell.backend.usercenter.config;

// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
// import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
// import org.springframework.security.oauth2.jwt.JwtDecoder;
// import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
// import org.springframework.security.web.SecurityFilterChain;

// import javax.crypto.spec.SecretKeySpec;
// import java.nio.charset.StandardCharsets;

// import static org.springframework.security.config.Customizer.withDefaults;

// @Configuration
// @EnableWebSecurity
// @EnableMethodSecurity
// public class SecurityConfig {
//     
//     @Value("${jwt.secret-key}")
//     private String secretKey;

//     @Bean
//     public JwtDecoder jwtDecoder() {
//         // 使用 HmacSHA256 算法生成 SecretKeySpec
//         SecretKeySpec keySpec = new SecretKeySpec(
//             secretKey.getBytes(StandardCharsets.UTF_8), 
//             "HmacSHA256"
//         );
//         return NimbusJwtDecoder.withSecretKey(keySpec).build();
//     }

//     @Bean
//     public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//         http
//             .csrf(AbstractHttpConfigurer::disable)
//             .authorizeHttpRequests(authorize -> authorize
//                 .requestMatchers("/api/auth/login", "/actuator/**", "/public/**").permitAll()
//                 .anyRequest().authenticated()
//             )
//             .oauth2Login(withDefaults()) // 启用 OAuth2 登录支持
//             .oauth2Client(withDefaults()) // 启用 OAuth2 Client 支持
//             .oauth2ResourceServer(oauth2 -> oauth2.jwt(withDefaults())); // 启用 JWT 校验 (Resource Server)

//         return http.build();
//     }
// }
