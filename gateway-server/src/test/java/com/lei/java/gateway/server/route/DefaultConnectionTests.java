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

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.server.route.connection.DefaultConnection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * 连接测试
 * </p>
 *
 * @author 伍磊
 */
public class DefaultConnectionTests {

    private static final Logger logger = LoggerFactory.getLogger(DefaultConnectionTests.class);
    private static final EventLoopGroup BOSS_GROUP =
            new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    private static final EventLoopGroup WORKER_GROUP =
            new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    private static final Random RANDOM = new Random();

    @AfterAll
    public static void afterAll() {
        BOSS_GROUP.shutdownGracefully();
        WORKER_GROUP.shutdownGracefully();
    }

    @Test
    public void testClose() throws InterruptedException, ExecutionException {
        int port = 8080 + RANDOM.nextInt(100);

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        CompletableFuture<Channel> serverChannelFuture = new CompletableFuture<>();
        // 用于保存服务端接受的客户端 Channel
        CompletableFuture<Channel> serverSideClientChannelFuture = new CompletableFuture<>();

        new Thread(() -> {
            serverBootstrap.group(BOSS_GROUP, WORKER_GROUP)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<NioSocketChannel>() {
                        @Override
                        protected void initChannel(NioSocketChannel ch) throws Exception {
                            // 保存服务端接受的客户端 Channel
                            serverSideClientChannelFuture.complete(ch);

                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new StringDecoder());
                            pipeline.addLast(new StringEncoder());
                            pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, String msg)
                                        throws Exception {
                                    logger.info("server received {}", msg);
                                    ctx.writeAndFlush(msg);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx)
                                        throws Exception {
                                    logger.info("Server side client channel inactive");
                                    super.channelInactive(ctx);
                                }
                            });
                        }
                    });
            ChannelFuture channelFuture = serverBootstrap.bind(port)
                    .syncUninterruptibly();
            Channel serverChannel = channelFuture.channel();
            serverChannelFuture.complete(serverChannel);
            serverChannel.closeFuture()
                    .syncUninterruptibly();
        }).start();

        serverChannelFuture.get();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(WORKER_GROUP)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new StringEncoder());
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg)
                                    throws Exception {
                                logger.info("client received {}", msg);
                                ctx.writeAndFlush(msg);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx)
                                    throws Exception {
                                logger.info("Client channel inactive");
                                super.channelInactive(ctx);
                            }
                        });
                    }
                });
        ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", port)
                .syncUninterruptibly();
        Channel clientChannel = channelFuture.channel();
        logger.info("client connected: {}", clientChannel);

        // 构建 DefaultConnection
        DefaultConnection defaultConnection = new DefaultConnection(
                bootstrap,
                clientChannel,
                new ServiceInstance("127.0.0.1", port));

        // wait 1s
        try {
            TimeUnit.SECONDS.sleep(1);

            // 先关闭服务端接受的客户端 Channel
            Channel serverSideClientChannel = serverSideClientChannelFuture.get();
            logger.info("Closing server side client channel");
            serverSideClientChannel.close()
                    .syncUninterruptibly();
            // channel 都已关闭
            TimeUnit.MILLISECONDS.sleep(50);
            assertThat(clientChannel.isActive()).isFalse();
            assertThat(defaultConnection.isActive()).isFalse();

            // 等待 2s 重连
            TimeUnit.SECONDS.sleep(2);
            assertThat(defaultConnection.isActive()).isTrue();
            assertThat(clientChannel.isActive()).isFalse();

        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testReconnectFailure() throws InterruptedException, ExecutionException {
        int port = 8080 + RANDOM.nextInt(100);

        // 启动服务端
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        CompletableFuture<Channel> serverChannelFuture = new CompletableFuture<>();
        CompletableFuture<Channel> serverSideClientChannelFuture = new CompletableFuture<>();

        new Thread(() -> {
            serverBootstrap.group(BOSS_GROUP, WORKER_GROUP)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<NioSocketChannel>() {
                        @Override
                        protected void initChannel(NioSocketChannel ch) throws Exception {
                            serverSideClientChannelFuture.complete(ch);
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new StringDecoder());
                            pipeline.addLast(new StringEncoder());
                            pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, String msg)
                                        throws Exception {
                                    ctx.writeAndFlush(msg);
                                }
                            });
                        }
                    });
            ChannelFuture channelFuture = serverBootstrap.bind(port)
                    .syncUninterruptibly();
            Channel serverChannel = channelFuture.channel();
            serverChannelFuture.complete(serverChannel);
            serverChannel.closeFuture()
                    .syncUninterruptibly();
        }).start();

        serverChannelFuture.get();

        // 启动客户端
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(WORKER_GROUP)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new StringEncoder());
                    }
                });
        ChannelFuture channelFuture = bootstrap.connect("127.0.0.1", port)
                .syncUninterruptibly();
        Channel clientChannel = channelFuture.channel();

        // 构建 DefaultConnection
        DefaultConnection defaultConnection = new DefaultConnection(
                bootstrap,
                clientChannel,
                new ServiceInstance("127.0.0.1", port));

        // 关闭
        serverSideClientChannelFuture.get()
                .close()
                .syncUninterruptibly();

        // 关闭服务端
        Channel serverChannel = serverChannelFuture.get();
        serverChannel.close()
                .syncUninterruptibly();

        // 等待重连超时
        TimeUnit.SECONDS.sleep(5);
        assertThat(defaultConnection.isActive()).isFalse();
        // 单次连接数量
        assertThat(defaultConnection.getRetryCount()
                .get()).isEqualTo(DefaultConnection.MAX_RETRY_TIMES);
    }

}
