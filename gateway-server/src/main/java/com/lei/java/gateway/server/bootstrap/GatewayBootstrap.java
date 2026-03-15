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
package com.lei.java.gateway.server.bootstrap;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.http.DefaultErrorResponseMapper;
import com.lei.java.gateway.server.http.DefaultHeaderPolicyService;
import com.lei.java.gateway.server.http.ErrorResponseMapper;
import com.lei.java.gateway.server.http.HeaderPolicyService;
import com.lei.java.gateway.server.metrics.GatewayMetricsService;
import com.lei.java.gateway.server.metrics.NoopGatewayMetricsService;
import com.lei.java.gateway.server.proxy.DefaultTimeoutPolicy;
import com.lei.java.gateway.server.proxy.TimeoutPolicy;
import com.lei.java.gateway.server.routing.RouteService;
import com.lei.java.gateway.server.routing.StaticRouteService;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/** 网关服务启动器，负责 Netty 服务生命周期管理。 */
public final class GatewayBootstrap {

    private static final int DEFAULT_BOSS_THREADS = 1;
    private static final int DEFAULT_BACKLOG = 1024;

    private final RouteService routeService;
    private final HeaderPolicyService headerPolicyService;
    private final TimeoutPolicy timeoutPolicy;
    private final ErrorResponseMapper errorResponseMapper;
    private final GatewayMetricsService gatewayMetricsService;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /** 使用默认实现构造启动器。 */
    public GatewayBootstrap() {
        this(
                new StaticRouteService(),
                new DefaultHeaderPolicyService(),
                new DefaultTimeoutPolicy(),
                new DefaultErrorResponseMapper(),
                new NoopGatewayMetricsService());
    }

    /**
     * 使用显式注入的依赖构造启动器。
     *
     * @param routeService 路由选择服务
     * @param headerPolicyService 请求头策略服务
     * @param timeoutPolicy 超时策略服务
     * @param errorResponseMapper 异常到错误响应映射服务
     * @param gatewayMetricsService 指标采集服务
     */
    public GatewayBootstrap(
            final RouteService routeService,
            final HeaderPolicyService headerPolicyService,
            final TimeoutPolicy timeoutPolicy,
            final ErrorResponseMapper errorResponseMapper,
            final GatewayMetricsService gatewayMetricsService) {
        this.routeService = Objects.requireNonNull(routeService, "routeService must not be null");
        this.headerPolicyService =
                Objects.requireNonNull(headerPolicyService, "headerPolicyService must not be null");
        this.timeoutPolicy =
                Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
        this.errorResponseMapper =
                Objects.requireNonNull(errorResponseMapper, "errorResponseMapper must not be null");
        this.gatewayMetricsService =
                Objects.requireNonNull(
                        gatewayMetricsService, "gatewayMetricsService must not be null");
    }

    /**
     * 启动网关服务。
     *
     * @param config 启动配置
     * @throws IllegalStateException 当服务已启动或端口绑定失败时抛出
     */
    public synchronized void start(final GatewayServerConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        if (isRunning()) {
            throw new IllegalStateException("gateway server is already running");
        }

        final EventLoopGroup newBossGroup =
                new MultiThreadIoEventLoopGroup(DEFAULT_BOSS_THREADS, NioIoHandler.newFactory());
        final EventLoopGroup newWorkerGroup =
                new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            final ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(newBossGroup, newWorkerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, DEFAULT_BACKLOG)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(
                            new io.netty.channel.ChannelInitializer<SocketChannel>() {
                                @Override
                                protected void initChannel(final SocketChannel channel) {
                                    new ServerPipelineFactory(
                                                    config,
                                                    routeService,
                                                    headerPolicyService,
                                                    timeoutPolicy,
                                                    errorResponseMapper,
                                                    gatewayMetricsService)
                                            .configure(channel.pipeline());
                                }
                            });

            if (config.pooledAllocatorEnabled()) {
                bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
                bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
            }

            final ChannelFuture bindFuture = bootstrap.bind(config.port()).syncUninterruptibly();
            if (!bindFuture.isSuccess()) {
                throw new IllegalStateException(
                        "failed to bind gateway server", bindFuture.cause());
            }

            this.bossGroup = newBossGroup;
            this.workerGroup = newWorkerGroup;
            this.serverChannel = bindFuture.channel();
        } catch (RuntimeException exception) {
            shutdownQuietly(newBossGroup);
            shutdownQuietly(newWorkerGroup);
            throw new IllegalStateException("failed to start gateway server", exception);
        }
    }

    /** 停止网关服务并释放资源。 */
    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
    }

    /**
     * 判断网关服务是否处于运行状态。
     *
     * @return 运行中返回 {@code true}，否则返回 {@code false}
     */
    public synchronized boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    /**
     * 返回实际绑定端口。
     *
     * @return 未启动时返回 {@code -1}，已启动返回绑定端口
     */
    public synchronized int boundPort() {
        if (serverChannel == null) {
            return -1;
        }
        final SocketAddress localAddress = serverChannel.localAddress();
        if (localAddress instanceof InetSocketAddress inetSocketAddress) {
            return inetSocketAddress.getPort();
        }
        return -1;
    }

    private static void shutdownQuietly(final EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }
}
