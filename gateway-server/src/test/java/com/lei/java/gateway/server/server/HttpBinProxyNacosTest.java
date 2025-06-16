package com.lei.java.gateway.server.server;

import com.alibaba.nacos.api.exception.NacosException;
import com.lei.java.gateway.server.GatewayServer;
import com.lei.java.gateway.server.route.ServiceRegistry;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.nacos.NacosConfig;
import com.lei.java.gateway.server.route.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;
import com.lei.java.gateway.server.test.NacosTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 代理 httpbin
 * </p>
 *
 * @author 伍磊
 */
@Testcontainers
public class HttpBinProxyNacosTest extends AbstractHttpBinProxyTest {

    private static final Logger logger = LoggerFactory.getLogger(HttpBinProxyNacosTest.class);

    @Container
    private static final NacosTestContainer nacosContainer = NacosTestContainer.getInstance();

    private static final int PORT = 8888;

    @BeforeAll
    static void setUp() throws InterruptedException {
        nacosContainer.start();
        logger.info("Nacos container started at: {}", nacosContainer.getServerAddr());
    }

    @Override
    protected int getPort() {
        return PORT;
    }

    @Override
    protected GatewayServer getGatewayServer() {
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        ConnectionManager connectionManager = new DefaultConnectionManager();
        NacosConfig config = NacosConfigLoader.load("nacos-test.properties");
        logger.info("Loaded Nacos config: serverAddr={}, namespace={}, group={}", config.getServerAddr(),
                        config.getNamespace(), config.getGroup());

        ServiceRegistry registry;
        try {
            registry = new NacosServiceRegistry(config);
            logger.info("Created NacosServiceRegistry successfully");
        } catch (NacosException e) {
            logger.error("Failed to create NacosServiceRegistry", e);
            throw new RuntimeException(e);
        }
        return new GatewayServer(PORT, registry, connectionManager);
    }
}
