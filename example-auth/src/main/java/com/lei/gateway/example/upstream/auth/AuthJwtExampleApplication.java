package com.lei.gateway.example.upstream.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * JWT 认证示例应用。
 */
@SpringBootApplication
@EnableConfigurationProperties(JwtExampleProperties.class)
public class AuthJwtExampleApplication {

    /**
     * 启动入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthJwtExampleApplication.class, args);
    }
}
