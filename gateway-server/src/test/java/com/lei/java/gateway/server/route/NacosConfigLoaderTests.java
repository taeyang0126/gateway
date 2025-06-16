package com.lei.java.gateway.server.route;

import com.lei.java.gateway.server.route.nacos.NacosConfig;
import com.lei.java.gateway.server.route.nacos.NacosConfigLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NacosConfigLoaderTests {

    @Test
    void testLoadDefaultConfig() {
        NacosConfig config = NacosConfigLoader.load("nacos.properties");

        assertNotNull(config, "配置不应为空");
        assertNotNull(config.getServerAddr(), "配置不应为空");
        assertEquals("public", config.getNamespace(), "命名空间不匹配");
        assertEquals("DEFAULT_GROUP", config.getGroup(), "分组不匹配");
    }

    @Test
    void testLoadCustomConfig() {
        NacosConfig config = NacosConfigLoader.load("nacos-test.properties");

        assertNotNull(config, "配置不应为空");
        assertNotNull(config.getServerAddr(), "配置不应为空");
        assertEquals("public", config.getNamespace());
        assertEquals("TEST_GROUP", config.getGroup());
    }
}