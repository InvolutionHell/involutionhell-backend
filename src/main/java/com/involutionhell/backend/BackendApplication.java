package com.involutionhell.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import com.involutionhell.backend.analytics.config.Ga4Properties;

@SpringBootApplication
@EnableCaching
@EnableAsync   // M4：启用 @Async，用于社区链接富化 worker 的异步执行
@EnableConfigurationProperties(Ga4Properties.class)
public class BackendApplication {

	/**
	 * 启动 Spring Boot 应用。
	 */
	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
