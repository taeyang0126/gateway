package com.lei.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.GatewayAutoConfiguration;
import com.lei.gateway.core.config.GatewayProperties;
import com.lei.gateway.core.proxy.ShutdownCoordinator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot 生命周期集成测试。
 *
 * <p>验证 Netty 服务随 Spring Boot 启动和关闭。
 * 使用内部配置类触发 component scan，加载所有网关 Bean。
 */
@SpringBootTest(classes = LifecycleIntegrationTest.TestConfig.class)
@ActiveProfiles("lifecycle")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LifecycleIntegrationTest {

    @Autowired
    private ShutdownCoordinator shutdownCoordinator;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void nettyStartsWithSpringBoot() {
        assertThat(shutdownCoordinator.isRunning()).isTrue();
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
        assertThat(shutdownCoordinator.isRunning()).isTrue();
        shutdownCoordinator.stop();
        assertThat(shutdownCoordinator.isRunning()).isFalse();
        // 重新启动以免影响其他测试
        shutdownCoordinator.start();
    }

    @Configuration
    @Import(GatewayAutoConfiguration.class)
    @ComponentScan("com.lei.gateway.core")
    static class TestConfig {
    }
}
