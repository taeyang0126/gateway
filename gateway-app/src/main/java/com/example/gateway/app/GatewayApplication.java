package com.example.gateway.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关启动入口。
 */
@SpringBootApplication(scanBasePackages = "com.example.gateway")
public class GatewayApplication {

    /**
     * 启动网关应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
