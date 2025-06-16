package com.lei.java.gateway.server.base;

/**
 * <p>
 * 基础的集成测试
 * </p>
 *
 * @author 伍磊
 */
public abstract class BaseIntegrationTest extends CommonMicroServiceTest {

    public static final NacosTestContainer nacosContainer = NacosTestContainer.getInstance();

}
