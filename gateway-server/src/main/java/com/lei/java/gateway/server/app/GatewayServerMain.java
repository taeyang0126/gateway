/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server.app;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.server.bootstrap.GatewayBootstrap;
import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.config.HostRewriteMode;
import com.lei.java.gateway.server.config.MatchType;
import com.lei.java.gateway.server.config.RouteConfig;
import com.lei.java.gateway.server.config.UpstreamConfig;

/**
 * 阶段 1 本地运行入口。
 *
 * <p>环境变量：
 *
 * <ul>
 *   <li>GATEWAY_PORT（默认 8080）
 *   <li>UPSTREAM_HOST（默认 127.0.0.1）
 *   <li>UPSTREAM_PORT（默认 9001）
 *   <li>ROUTE_PREFIX（默认 /api/）
 *   <li>CONNECT_TIMEOUT_MS（默认 1000）
 *   <li>READ_TIMEOUT_MS（默认 1000）
 *   <li>WRITE_TIMEOUT_MS（默认 1000）
 * </ul>
 */
public final class GatewayServerMain {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayServerMain.class);

    private GatewayServerMain() {
        // utility
    }

    /**
     * 启动网关并保持进程存活。
     *
     * @param args 命令行参数（未使用）
     * @throws InterruptedException 线程中断
     */
    public static void main(final String[] args) throws InterruptedException {
        final int port = getIntEnv("GATEWAY_PORT", 8080);
        final String upstreamHost = getStringEnv("UPSTREAM_HOST", "127.0.0.1");
        final int upstreamPort = getIntEnv("UPSTREAM_PORT", 9001);
        final String routePrefix = getStringEnv("ROUTE_PREFIX", "/api/");
        final int connectTimeoutMs = getIntEnv("CONNECT_TIMEOUT_MS", 1000);
        final int readTimeoutMs = getIntEnv("READ_TIMEOUT_MS", 1000);
        final int writeTimeoutMs = getIntEnv("WRITE_TIMEOUT_MS", 1000);

        final RouteConfig route =
                new RouteConfig(
                        "route-main",
                        100,
                        MatchType.PREFIX,
                        routePrefix,
                        HostRewriteMode.REWRITE,
                        new UpstreamConfig(
                                "http",
                                upstreamHost,
                                upstreamPort,
                                connectTimeoutMs,
                                readTimeoutMs,
                                writeTimeoutMs));

        final GatewayServerConfig config =
                new GatewayServerConfig(port, 1024 * 1024, true, List.of(route));
        final GatewayBootstrap bootstrap = new GatewayBootstrap();
        bootstrap.start(config);

        Runtime.getRuntime().addShutdownHook(new Thread(bootstrap::stop, "gateway-stop-hook"));

        LOGGER.info(
                "gateway started, port={}, upstream={}:{}, routePrefix={}",
                bootstrap.boundPort(),
                upstreamHost,
                upstreamPort,
                routePrefix);

        while (bootstrap.isRunning()) {
            Thread.sleep(1_000L);
        }
    }

    private static int getIntEnv(final String name, final int defaultValue) {
        final String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static String getStringEnv(final String name, final String defaultValue) {
        final String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return raw.trim();
    }
}
