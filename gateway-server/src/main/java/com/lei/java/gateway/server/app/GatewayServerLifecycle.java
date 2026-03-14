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

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.lei.java.gateway.server.bootstrap.GatewayBootstrap;
import com.lei.java.gateway.server.config.GatewayServerConfig;

/** Spring 生命周期桥接，负责网关随容器启动与停止。 */
public final class GatewayServerLifecycle implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayServerLifecycle.class);

    private final GatewayBootstrap bootstrap;
    private final GatewayServerConfig gatewayServerConfig;

    private volatile boolean running;

    public GatewayServerLifecycle(
            final GatewayBootstrap bootstrap, final GatewayServerConfig gatewayServerConfig) {
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap must not be null");
        this.gatewayServerConfig =
                Objects.requireNonNull(gatewayServerConfig, "gatewayServerConfig must not be null");
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        bootstrap.start(gatewayServerConfig);
        running = true;
        LOGGER.info(
                "gateway started, port={}, routeCount={}",
                bootstrap.boundPort(),
                gatewayServerConfig.routes().size());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        bootstrap.stop();
        running = false;
        LOGGER.info("gateway stopped");
    }

    @Override
    public void stop(final Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running && bootstrap.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
