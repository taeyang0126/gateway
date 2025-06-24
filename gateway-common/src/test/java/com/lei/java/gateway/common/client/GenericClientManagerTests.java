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
package com.lei.java.gateway.common.client;

import io.netty.bootstrap.ServerBootstrap;
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
import io.netty.util.Timer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.route.ServiceInstance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * GenericNettyClientManagerTests
 * </p>
 *
 * @author 伍磊
 */
public class GenericClientManagerTests {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenericClientManagerTests.class);
    private static final EventLoopGroup BOSS_GROUP =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private static final EventLoopGroup WORKER_GROUP =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private static final int SERVER_PORT = 7890;

    @BeforeAll
    public static void beforeAll() {
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(BOSS_GROUP, WORKER_GROUP)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new StringEncoder());
                        pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, String msg)
                                    throws Exception {
                                LOGGER.info("server receive msg: {}", msg);
                                ctx.channel()
                                        .writeAndFlush(msg);
                            }
                        });
                    }
                });
        serverBootstrap.bind(SERVER_PORT)
                .syncUninterruptibly();
        LOGGER.info("server start success @{}", SERVER_PORT);
    }

    @AfterAll
    public static void afterAll() {
        BOSS_GROUP.shutdownGracefully();
        WORKER_GROUP.shutdownGracefully();
    }

    @Test
    public void test_client() throws InterruptedException {
        GenericClientManager.ClientFactory<ClientExample> clientFactory = ClientExample::new;
        ClientManager<ClientExample> clientManager = new GenericClientManager<>(clientFactory);

        ServiceInstance serviceInstance = new ServiceInstance("127.0.0.1", SERVER_PORT);
        ClientExample clientExample = clientManager.getClient(serviceInstance)
                .join();
        assertThat(clientExample).isNotNull()
                .matches(AbstractClient::isActive);

        ChannelFuture channelFuture = clientExample.getChannel()
                .writeAndFlush("hello world")
                .sync();
        assertThat(channelFuture.isSuccess()).isTrue();

        ChannelFuture closeFuture = clientExample.getChannel()
                .closeFuture();
        clientManager.removeClient(clientExample);

        closeFuture.syncUninterruptibly();
        assertThat(clientExample).isNotNull()
                .matches(t -> !t.isActive());
    }

    @Test
    public void test_close() throws InterruptedException {
        GenericClientManager.ClientFactory<ClientExample> clientFactory = ClientExample::new;
        ClientManager<ClientExample> clientManager = new GenericClientManager<>(clientFactory);

        ServiceInstance serviceInstance = new ServiceInstance("127.0.0.1", SERVER_PORT);
        ClientExample clientExample = clientManager.getClient(serviceInstance)
                .join();
        assertThat(clientExample).isNotNull()
                .matches(AbstractClient::isActive);

        ChannelFuture channelFuture = clientExample.getChannel()
                .writeAndFlush("hello world")
                .sync();
        assertThat(channelFuture.isSuccess()).isTrue();

        ChannelFuture closeFuture = clientExample.getChannel()
                .closeFuture();
        clientManager.close();

        closeFuture.syncUninterruptibly();
        assertThat(clientExample).isNotNull()
                .matches(t -> !t.isActive());
    }

    public static class ClientExample extends AbstractClient<ClientExample> {

        public ClientExample(ServiceInstance instance, EventLoopGroup group, Timer timer) {
            super(instance, group, timer);
        }

        @Override
        protected void initBusinessHandlers(ChannelPipeline pipeline) {
            pipeline.addLast(new StringDecoder());
            pipeline.addLast(new StringEncoder());
            pipeline.addLast(new SimpleChannelInboundHandler<String>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, String msg)
                        throws Exception {
                    LOGGER.info("client receive msg: {}", msg);
                }
            });
        }
    }
}
