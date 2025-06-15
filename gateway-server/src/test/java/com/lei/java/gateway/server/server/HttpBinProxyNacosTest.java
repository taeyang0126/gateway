package com.lei.java.gateway.server.server;

import com.alibaba.nacos.api.exception.NacosException;
import com.lei.java.gateway.server.GatewayServer;
import com.lei.java.gateway.server.route.ServiceRegistry;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.nacos.NacosConfig;
import com.lei.java.gateway.server.route.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;

/**
 * <p>
 * 代理 httpbin
 * </p>
 *
 * @author 伍磊
 */
public class HttpBinProxyNacosTest extends AbstractHttpBinProxyTest {
    private static final int PORT = 8888;

    @Override
    protected int getPort() {
        return PORT;
    }

    @Override
    protected GatewayServer getGatewayServer() {
        ConnectionManager connectionManager = new DefaultConnectionManager();
        NacosConfig config = NacosConfigLoader.load("nacos-test.properties");
        ServiceRegistry registry;
        try {
            registry = new NacosServiceRegistry(config);
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
        return new GatewayServer(PORT, registry, connectionManager);
    }

}
