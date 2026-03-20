package com.example.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.gateway.core.config.GatewayAutoConfiguration;
import com.example.gateway.core.config.GatewayProperties;
import com.example.gateway.core.proxy.NettyServerBootstrap;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot 生命周期集成测试。
 *
 * <p>验证 Netty 服务随 Spring Boot 启动和关闭。
 * 使用内部配置类触发 component scan，加载所有网关 Bean。
 */
@SpringBootTest(classes = LifecycleIntegrationTest.TestConfig.class)
@ActiveProfiles("lifecycle")
class LifecycleIntegrationTest {

    @Autowired
    private NettyServerBootstrap nettyServerBootstrap;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void nettyStartsWithSpringBoot() {
        assertThat(nettyServerBootstrap.isRunning()).isTrue();
    }

    @Test
    void healthEndpointAccessibleAfterStartup()
            throws Exception {
        int port = gatewayProperties.getPort();
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://localhost:" + port
                            + "/health"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"UP\"");
        }
    }

    @Test
    void nettyStopsWhenSpringBootCloses() {
        assertThat(nettyServerBootstrap.isRunning()).isTrue();
        nettyServerBootstrap.stop();
        assertThat(nettyServerBootstrap.isRunning()).isFalse();
        // 重新启动以免影响其他测试
        nettyServerBootstrap.start();
    }

    @Configuration
    @Import(GatewayAutoConfiguration.class)
    @ComponentScan("com.example.gateway.core")
    static class TestConfig {

        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.prometheusmetrics
                    .PrometheusMeterRegistry(
                    io.micrometer.prometheusmetrics
                            .PrometheusConfig.DEFAULT);
        }
    }
}
