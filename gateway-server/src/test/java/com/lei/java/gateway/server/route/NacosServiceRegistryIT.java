package com.lei.java.gateway.server.route;

import com.alibaba.nacos.api.exception.NacosException;
import com.lei.java.gateway.server.base.BaseIntegrationTest;
import com.lei.java.gateway.server.route.nacos.NacosConfig;
import com.lei.java.gateway.server.route.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NacosServiceRegistryIT extends BaseIntegrationTest {

    private static String TEST_SERVICE_NAME;
    private static NacosConfig nacosConfig;
    private static ServiceRegistry serviceRegistry;
    private static ServiceInstance testInstance;

    @BeforeAll
    public static void init() throws NacosException {
        nacosConfig = NacosConfigLoader.load("nacos-test.properties");
        serviceRegistry = new NacosServiceRegistry(nacosConfig);
        TEST_SERVICE_NAME = "nacos-service-registry-test" + System.nanoTime();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("environment", "test");
        testInstance = new ServiceInstance("127.0.0.1", 8080, metadata);
    }

    @AfterAll
    public static void clear() {
        serviceRegistry.removeService(TEST_SERVICE_NAME, testInstance);
        List<ServiceInstance> existingServices = serviceRegistry.getServices(TEST_SERVICE_NAME);
        if (!existingServices.isEmpty()) {
            for (ServiceInstance instance : existingServices) {
                serviceRegistry.removeService(TEST_SERVICE_NAME, instance);
            }
        }
    }

    @Test
    public void test() throws Exception {
        serviceRegistry.registerService(TEST_SERVICE_NAME, testInstance);
        List<ServiceInstance> services = serviceRegistry.getServices(TEST_SERVICE_NAME);
        assertNotNull(services, "获取服务列表失败");
        assertFalse(services.isEmpty(), "服务列表为空");
        ServiceInstance registeredInstance = services.get(0);
        assertEquals(testInstance.getHost(), registeredInstance.getHost(), "服务实例Host不匹配");
        assertEquals(testInstance.getPort(), registeredInstance.getPort(), "服务实例Port不匹配");

        serviceRegistry.removeService(TEST_SERVICE_NAME, testInstance);
        TimeUnit.SECONDS.sleep(1);
        List<ServiceInstance> servicesAfterRemoval = serviceRegistry.getServices(TEST_SERVICE_NAME);
        assertTrue(servicesAfterRemoval.isEmpty(), "服务未能成功注销");
    }

}
