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
package com.lei.java.gateway.server.route;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.loadbalancer.RoundRobinLoadBalancer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class HttpRouteServiceTests {

    private static final int UPSTREAM_PORT = 18080;
    private static final String UPSTREAM_HOST = "127.0.0.1";
    private static final String TEST_BIZ_TYPE = "test.http.service";
    private static final String TEST_URL = "/test/http/service";
    private static final EventLoopGroup BOSS_GROUP =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private static final EventLoopGroup WORKER_GROUP =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private static final EchoHttpServerHandler ECHO_HTTP_SERVER_HANDLER =
            new EchoHttpServerHandler();
    private static ServiceRegistry registry;
    private static ConnectionManager connectionManager;
    private static RouteService routeService;

    @BeforeEach
    public void before() {
        // 1. 构建一个上游的 http server
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(BOSS_GROUP, WORKER_GROUP)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65535));
                        pipeline.addLast(ECHO_HTTP_SERVER_HANDLER);
                    }
                });
        serverBootstrap.bind(UPSTREAM_PORT)
                .syncUninterruptibly();

        // 2. ConnectionManager
        connectionManager = new DefaultConnectionManager();

        // 3. ServiceRegistry
        registry = new DefaultServiceRegistry(connectionManager);
        registry.registerService(TEST_BIZ_TYPE, new ServiceInstance(UPSTREAM_HOST, UPSTREAM_PORT));

        // 4. routeService
        routeService =
                new DefaultRouteService(registry, new RoundRobinLoadBalancer(), connectionManager);
    }

    @AfterEach
    public void after() {
        BOSS_GROUP.shutdownGracefully();
        WORKER_GROUP.shutdownGracefully();
        connectionManager.close();
        registry.close();
    }

    @Test
    public void testHttpRouteService()
            throws ExecutionException, InterruptedException, TimeoutException {
        GatewayMessage message = new GatewayMessage();
        message.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        message.setRequestId(System.currentTimeMillis());
        message.setClientId(UUID.randomUUID()
                .toString());
        message.setBizType(TEST_BIZ_TYPE);
        message.setBody("{\"name\":\"嘻嘻嘻\",\"age\":123}".getBytes(StandardCharsets.UTF_8));

        // 发送请求
        CompletableFuture<GatewayMessage> responseFuture = routeService.route(message);

        // 等待响应并验证
        GatewayMessage gatewayMessage = responseFuture.get(5, TimeUnit.SECONDS);

        assertThat(gatewayMessage).isNotNull();
        assertThat(gatewayMessage.getRequestId()).isEqualTo(message.getRequestId());
        assertThat(gatewayMessage.getClientId()).isEqualTo(message.getClientId());
        assertThat(gatewayMessage.getBizType()).isEqualTo(message.getBizType());
        assertThat(gatewayMessage.getExtensions()
                .get("http_status")).isEqualTo("200");
        assertThat(gatewayMessage.getBody()).isEqualTo(message.getBody());
    }

    @Test
    public void testHttpRouteServiceWithInvalidBizType()
            throws ExecutionException, InterruptedException {
        GatewayMessage message = new GatewayMessage();
        message.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        message.setRequestId(System.currentTimeMillis());
        message.setClientId(UUID.randomUUID()
                .toString());
        message.setBizType(UUID.randomUUID()
                .toString());
        message.setBody("{\"name\":\"嘻嘻嘻\",\"age\":123}".getBytes(StandardCharsets.UTF_8));

        // 发送请求
        CompletableFuture<GatewayMessage> future = routeService.route(message);

        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.format(DefaultRouteService.ERROR_SERVICE_NOT_FOUND,
                        message.getBizType()));
    }

    @ChannelHandler.Sharable
    public static class EchoHttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg)
                throws Exception {

            String uri = msg.uri();
            if (!uri.endsWith(TEST_URL)) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.NOT_FOUND);
                response.headers()
                        .set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                ctx.writeAndFlush(response);
                return;
            }

            ByteBuf buf = msg.content()
                    .retainedDuplicate();
            FullHttpResponse response =
                    new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    .set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            ctx.writeAndFlush(response);
        }
    }

}