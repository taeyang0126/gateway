package com.lei.java.gateway.server.server;

import com.lei.java.gateway.server.GatewayServer;

/**
 * <p>
 * 代理 httpbin
 * </p>
 *
 * @author 伍磊
 */
public class HttpBinProxyIT extends AbstractHttpBinProxyTest {
    private static final int PORT = 8889;

    @Override
    protected int getPort() {
        return PORT;
    }

    @Override
    protected GatewayServer getGatewayServer() {
        return new GatewayServer(PORT);
    }
}
