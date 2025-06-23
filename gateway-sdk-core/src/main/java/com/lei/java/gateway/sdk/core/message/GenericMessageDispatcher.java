/*
 * Copyright (c) 2025 The gateway Project
 * https://github.com/taeyang0126/gateway
 *
 * Licensed under the MIT License.
 * You may obtain a copy of the License at
 *
 *     https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.sdk.core.message;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.codec.GatewayMessageCodec;
import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.sdk.core.connection.GatewayConnection;
import com.lei.java.gateway.sdk.core.exception.GatewayConnectionException;
import com.lei.java.gateway.sdk.core.exception.RecipientNotReachableException;
import com.lei.java.gateway.sdk.core.route.ClientGatewayLocator;

import static com.lei.java.gateway.common.config.security.SecurityConfig.INNER_TOKEN_VALUE;
import static com.lei.java.gateway.common.config.security.SecurityConfig.TOKEN_NAME;
import static com.lei.java.gateway.common.constants.GatewayConstant.GATEWAY_READ_IDLE_TIMEOUT_SECONDS;

/**
 * <p>
 * GenericMessageDispatcher
 * </p>
 *
 * @author 伍磊
 */
public class GenericMessageDispatcher implements MessageDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenericMessageDispatcher.class);
    private final ClientGatewayLocator clientGatewayLocator;
    private final Map<ServiceInstance, GatewayConnection> connections;
    private final Map<ServiceInstance, CompletableFuture<GatewayConnection>> pendingConnections;
    private final ThreadFactory createConnectionFactory;
    private final EventLoopGroup workerGroup;
    private final Bootstrap bootstrap;
    private volatile boolean closed;

    public GenericMessageDispatcher(ClientGatewayLocator clientGatewayLocator) {
        this.closed = false;
        this.clientGatewayLocator = clientGatewayLocator;
        this.connections = new ConcurrentHashMap<>();
        this.pendingConnections = new ConcurrentHashMap<>();
        this.workerGroup = new MultiThreadIoEventLoopGroup(
                Runtime.getRuntime()
                        .availableProcessors(),
                NioIoHandler.newFactory());
        this.createConnectionFactory = Thread.ofVirtual()
                .name("gateway-connection-create-", 0)
                .uncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught exception", e))
                .factory();

        this.bootstrap = new Bootstrap();
        // 初始化Bootstrap
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new IdleStateHandler(
                                0,
                                GATEWAY_READ_IDLE_TIMEOUT_SECONDS / 2,
                                0,
                                TimeUnit.SECONDS));
                        // 添加消息编解码器
                        p.addLast(new GatewayMessageCodec());
                    }
                });
    }

    @Override
    public CompletableFuture<Void> dispatch(String clientId, byte[] content) {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        Optional<ServiceInstance> gatewayNodeOptional =
                clientGatewayLocator.findByClientId(clientId);
        if (gatewayNodeOptional.isEmpty()) {
            completableFuture.completeExceptionally(new RecipientNotReachableException(
                    "Recipient not found or offline for client: "
                            + clientId,
                    clientId));
            return completableFuture;
        }

        ServiceInstance gatewayNode = gatewayNodeOptional.get();
        GatewayConnection gatewayConnection = connections.get(gatewayNode);
        if (gatewayConnection != null) {
            gatewayConnection.send(clientId, content, completableFuture);
            return completableFuture;
        }

        // create new connection
        pendingConnections.computeIfAbsent(gatewayNode, k -> {
            LOGGER.info("Creating new connection for {}", gatewayNode);
            CompletableFuture<GatewayConnection> future = new CompletableFuture<>();
            createConnectionFactory.newThread(() -> {
                bootstrap.connect(gatewayNode.getHost(), gatewayNode.getPort())
                        .addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture f) throws Exception {
                                if (f.isSuccess()) {
                                    // 发送 auth 消息
                                    // auth 通过后再算连接建立完成
                                    // todo-wl 这里没有验证返回值
                                    GatewayMessage gatewayMessage = getAuthMessage();
                                    f.channel()
                                            .writeAndFlush(gatewayMessage)
                                            .addListener(new ChannelFutureListener() {
                                                @Override
                                                public void operationComplete(
                                                        ChannelFuture authFuture) throws Exception {
                                                    if (authFuture.isSuccess()) {
                                                        GatewayConnection gatewayConnection =
                                                                new GatewayConnection(f.channel());
                                                        connections.put(gatewayNode,
                                                                gatewayConnection);
                                                        future.complete(gatewayConnection);
                                                        LOGGER.info("Create new connection for {}",
                                                                gatewayNode);
                                                    } else {
                                                        future.completeExceptionally(f.cause());
                                                    }
                                                }
                                            });
                                } else {
                                    future.completeExceptionally(f.cause());
                                }
                                pendingConnections.remove(gatewayNode, future);
                            }
                        });
            })
                    .start();
            return future;
        })
                .whenComplete((connection, throwable) -> {
                    if (throwable != null) {
                        completableFuture.completeExceptionally(new GatewayConnectionException(
                                "gateway can not connected",
                                throwable));
                        return;
                    }
                    connection.send(clientId, content, completableFuture);
                });
        return completableFuture;
    }

    @Override
    public void shutdown() {
        if (!closed) {
            closed = true;
            connections.values()
                    .forEach(GatewayConnection::close);
            connections.clear();
            workerGroup.shutdownGracefully();
            LOGGER.info("MessageDispatcher shutdown");
        }
    }

    private static GatewayMessage getAuthMessage() {
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH);
        gatewayMessage.setRequestId(System.currentTimeMillis());
        gatewayMessage.setClientId(UUID.randomUUID()
                .toString());
        gatewayMessage.getExtensions()
                .put(TOKEN_NAME, INNER_TOKEN_VALUE);
        return gatewayMessage;
    }
}
