package com.lei.gateway.example.upstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mock 上游服务启动类，用于网关开发和测试。
 */
@SpringBootApplication
public class ExampleApplication {

    /**
     * 启动 mock 上游服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
