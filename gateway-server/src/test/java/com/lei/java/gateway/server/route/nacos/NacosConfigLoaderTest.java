package com.lei.java.gateway.server.route.nacos;

import com.lei.java.gateway.server.test.NacosTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers
class NacosConfigLoaderTest {

    @Container
    private static final NacosTestContainer nacosContainer = NacosTestContainer.getInstance();

    @BeforeAll
    static void setUp() {
        nacosContainer.start();
    }

    @Test
    void testLoadDefaultConfig() {
        NacosConfig config = NacosConfigLoader.load("nacos.properties");

        assertNotNull(config, "配置不应为空");
        assertNotEquals(nacosContainer.getServerAddr(), config.getServerAddr(), "服务器地址匹配");
        assertEquals("public", config.getNamespace(), "命名空间不匹配");
        assertEquals("DEFAULT_GROUP", config.getGroup(), "分组不匹配");
    }

    @Test
    void testLoadCustomConfig() {
        NacosConfig config = NacosConfigLoader.load("nacos-test.properties");

        assertNotNull(config, "配置不应为空");
        assertEquals(nacosContainer.getServerAddr(), config.getServerAddr());
        assertEquals("public", config.getNamespace());
        assertEquals("TEST_GROUP", config.getGroup());
    }
}