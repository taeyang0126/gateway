package com.lei.java.gateway.server.base;

import org.junit.jupiter.api.AfterAll;

/**
 * <p>
 * 基础的集成测试
 * </p>
 *
 * @author 伍磊
 */
public abstract class BaseIntegrationTest extends CommonMicroServiceTest {

    public static final NacosTestContainer nacosContainer = NacosTestContainer.getInstance();

    @AfterAll
    static void tearDownAll() {
        // ...
        // 如果容器对象存在但没有在运行，说明它在中途崩溃了
        if (nacosContainer != null && !nacosContainer.isRunning()) {
            System.err.println("!!!!!!!!!! NACOS CONTAINER CRASHED !!!!!!!!!!");
            System.err.println("=== DUMPING LOGS FROM CRASHED CONTAINER: ===");
            // 打印容器生命周期内的所有标准输出和错误输出
            System.err.println(nacosContainer.getLogs());
            System.err.println("============================================");
        } else if (nacosContainer != null) {
            nacosContainer.stop();
        }
        // ...
    }
}
