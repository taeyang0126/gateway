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
package com.lei.java.gateway.server.server;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.codec.GatewayMessageCodec;
import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.server.GatewayServer;
import com.lei.java.gateway.server.auth.DefaultAuthService;
import com.lei.java.gateway.server.route.RouteService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * <p>
 * GatewayServerTest
 * </p>
 *
 * @author 伍磊
 */
@ExtendWith(MockitoExtension.class)
public class GatewayServerIT {

    private static final Logger logger = LoggerFactory.getLogger(HttpBinProxyIT.class);

    @Mock
    private RouteService routeService;

    public static final String SERVER_HOST = "127.0.0.1";
    private EventLoopGroup eventLoopGroup =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private GatewayServer gatewayServer;
    private Map<Long, CompletableFuture<GatewayMessage>> pendingRequests;
    private Channel clientChannel;
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private int port;

    @BeforeEach
    public void setup() throws Exception {
        port = 17000 + COUNTER.incrementAndGet();
        this.eventLoopGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.pendingRequests = new ConcurrentHashMap<>();

        // 1. GatewayServer
        CompletableFuture<Void> future = new CompletableFuture<>();
        gatewayServer = new GatewayServer(port, routeService);
        Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("gateway-server-"
                    + COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        })
                .execute(() -> {
                    try {
                        gatewayServer.start(future);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        future.get();

        // 2. client Channel
        clientChannel = initClient();
    }

    @AfterEach
    public void teardown() throws InterruptedException {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully()
                    .sync();
        }
        if (clientChannel != null && clientChannel.isOpen()) {
            clientChannel.close()
                    .sync();
        }
        clientChannel = null;
        pendingRequests.clear();
        if (gatewayServer != null) {
            gatewayServer.shutdown();
        }
        // 添加一个短暂的等待，确保资源完全释放
        TimeUnit.MILLISECONDS.sleep(100);
    }

    @Test
    // @Order(1)
    public void testNoAuth() throws Exception {
        // 发送心跳消息
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_HEARTBEAT);
        gatewayMessage.setRequestId(System.currentTimeMillis());
        gatewayMessage.setClientId(UUID.randomUUID()
                .toString());
        CompletableFuture<GatewayMessage> responseFuture = writeMsg(gatewayMessage);

        // 等待消息回来
        GatewayMessage response = responseFuture.get();

        assertThat(response).isNotNull();
        assertThat(response.getClientId()).isEqualTo(gatewayMessage.getClientId());
        assertThat(response.getRequestId()).isEqualTo(gatewayMessage.getRequestId());
        assertThat(response.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_AUTH_FAIL_RESP);
    }

    @Test
    // @Order(2)
    public void testAuth() throws Exception {
        // 发送认证消息
        doAuth();
    }

    @Test
    // @Order(3)
    public void testHeartbeat() throws Exception {
        // 1. 先发送认证消息
        doAuth();

        // 2. 发送心跳消息
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_HEARTBEAT);
        gatewayMessage.setRequestId(System.currentTimeMillis());
        gatewayMessage.setClientId(UUID.randomUUID()
                .toString());
        CompletableFuture<GatewayMessage> heartBeatFuture = writeMsg(gatewayMessage);
        GatewayMessage response = heartBeatFuture.get();

        assertThat(response).isNotNull();
        assertThat(response.getClientId()).isEqualTo(gatewayMessage.getClientId());
        assertThat(response.getRequestId()).isEqualTo(gatewayMessage.getRequestId());
        assertThat(response.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_HEARTBEAT);
    }

    @Test
    // @Order(4)
    public void testBizMsg_errorBizType() throws Exception {
        CompletableFuture<GatewayMessage> mockFuture = new CompletableFuture<>();
        mockFuture.completeExceptionally(new IllegalArgumentException());
        when(routeService.route(any())).thenReturn(mockFuture);

        // 1. 先发送认证消息
        doAuth();

        // 2. 发送业务消息
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        gatewayMessage.setRequestId(System.currentTimeMillis());
        gatewayMessage.setClientId(UUID.randomUUID()
                .toString());

        CompletableFuture<GatewayMessage> bizMsgFuture = writeMsg(gatewayMessage);
        GatewayMessage responseMsg = bizMsgFuture.get();

        assertThat(responseMsg).isNotNull();
        assertThat(responseMsg.getClientId()).isEqualTo(gatewayMessage.getClientId());
        assertThat(responseMsg.getRequestId()).isEqualTo(gatewayMessage.getRequestId());
        assertThat(responseMsg.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_ERROR);
    }

    @Test
    // @Order(5)
    public void testBizMsg() throws Exception {
        String clientId = UUID.randomUUID()
                .toString();
        long requestId = System.currentTimeMillis();
        String responseContent = UUID.randomUUID()
                .toString();

        CompletableFuture<GatewayMessage> mockFuture = new CompletableFuture<>();
        GatewayMessage mockResponseMsg = new GatewayMessage();
        mockResponseMsg.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        mockResponseMsg.setRequestId(requestId);
        mockResponseMsg.setClientId(clientId);
        mockResponseMsg.setBody(responseContent.getBytes(StandardCharsets.UTF_8));
        mockFuture.complete(mockResponseMsg);
        when(routeService.route(any())).thenReturn(mockFuture);

        // 1. 先发送认证消息
        testAuth();

        // 2. 发送业务消息
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        gatewayMessage.setRequestId(requestId);
        gatewayMessage.setClientId(clientId);

        CompletableFuture<GatewayMessage> bizMsgFuture = writeMsg(gatewayMessage);
        GatewayMessage responseMsg = bizMsgFuture.get();

        assertThat(responseMsg).isNotNull();
        assertThat(responseMsg.getClientId()).isEqualTo(gatewayMessage.getClientId());
        assertThat(responseMsg.getRequestId()).isEqualTo(gatewayMessage.getRequestId());
        assertThat(responseMsg.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_BIZ);
        assertThat(responseMsg.getBody())
                .isEqualTo(responseContent.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    // @Order(6)
    public void testMessageOrder() throws Exception {
        when(routeService.route(any(GatewayMessage.class)))
                .thenAnswer((Answer<CompletableFuture<GatewayMessage>>) invocation -> {
                    GatewayMessage request = invocation.getArgument(0);

                    GatewayMessage mockResponseMsg = new GatewayMessage();
                    mockResponseMsg.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
                    mockResponseMsg.setRequestId(request.getRequestId());
                    mockResponseMsg.setClientId(request.getClientId());
                    mockResponseMsg.setBody(request.getBody());

                    CompletableFuture<GatewayMessage> concurrentFuture = new CompletableFuture<>();
                    concurrentFuture.complete(mockResponseMsg);
                    return concurrentFuture;
                });

        doAuth(); // 先认证

        // 发送多条顺序消息
        int messageCount = 5;
        List<GatewayMessage> messages = new ArrayList<>();
        List<CompletableFuture<GatewayMessage>> futures = new ArrayList<>();

        for (int i = 0; i < messageCount; i++) {
            GatewayMessage msg = new GatewayMessage();
            msg.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
            msg.setRequestId(System.currentTimeMillis() + i); // 确保唯一
            msg.setClientId(UUID.randomUUID()
                    .toString());
            msg.setBody(String.valueOf(i)
                    .getBytes(StandardCharsets.UTF_8));

            messages.add(msg);
            futures.add(writeMsg(msg));
        }

        // 验证响应顺序
        for (int i = 0; i < messageCount; i++) {
            GatewayMessage response = futures.get(i)
                    .get(5, TimeUnit.SECONDS);
            assertThat(response.getRequestId()).isEqualTo(messages.get(i)
                    .getRequestId());
            assertThat(response.getMsgType()).isEqualTo(messages.get(i)
                    .getMsgType());
        }
    }

    @Test
    // @Order(7)
    public void testConcurrencyMessage() throws Exception {
        when(routeService.route(any(GatewayMessage.class)))
                .thenAnswer((Answer<CompletableFuture<GatewayMessage>>) invocation -> {
                    GatewayMessage request = invocation.getArgument(0);

                    GatewayMessage mockResponseMsg = new GatewayMessage();
                    mockResponseMsg.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
                    mockResponseMsg.setRequestId(request.getRequestId());
                    mockResponseMsg.setClientId(request.getClientId());
                    mockResponseMsg.setBody(request.getBody());

                    // c. 返回修改后的对象
                    CompletableFuture<GatewayMessage> concurrentFuture = new CompletableFuture<>();
                    concurrentFuture.complete(mockResponseMsg);
                    return concurrentFuture;
                });

        doAuth(); // 先认证

        // 发送多条顺序消息
        int messageCount = 1000;
        CountDownLatch latch = new CountDownLatch(messageCount);
        List<CompletableFuture<GatewayMessage>> futures = new ArrayList<>();

        for (int i = 0; i < messageCount; i++) {
            GatewayMessage msg = new GatewayMessage();
            msg.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
            // msg.setRequestId(System.currentTimeMillis() +
            // ThreadLocalRandom.current().nextInt(100) + i); // 确保唯一
            msg.setRequestId(System.currentTimeMillis() + i); // 确保唯一
            msg.setClientId(UUID.randomUUID()
                    .toString());
            msg.setBody(String.valueOf(i)
                    .getBytes(StandardCharsets.UTF_8));

            futures.add(writeMsg(msg));
            latch.countDown();
        }

        latch.await();
        logger.info("send finished");

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]))
                .join();
        logger.info("finished");

    }

    @Test
    // @Order(8)
    public void testReconnect() throws Exception {
        doAuth(); // 先认证

        // 断开连接
        clientChannel.close()
                .sync();

        // 重新连接
        clientChannel = initClient();

        // 验证需要重新认证
        GatewayMessage heartbeat = new GatewayMessage();
        heartbeat.setMsgType(GatewayMessage.MESSAGE_TYPE_HEARTBEAT);
        heartbeat.setRequestId(System.currentTimeMillis());

        CompletableFuture<GatewayMessage> future = writeMsg(heartbeat);
        GatewayMessage response = future.get(5, TimeUnit.SECONDS);

        assertThat(response.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_AUTH_FAIL_RESP);
    }

    @Test
    // @Order(9)
    public void testLargeMessage() throws Exception {
        // 构造大消息
        byte[] largeContent = new byte[1024 * 1024]; // 1MB
        new Random().nextBytes(largeContent);

        String clientId = UUID.randomUUID()
                .toString();
        long requestId = System.currentTimeMillis();

        CompletableFuture<GatewayMessage> mockFuture = new CompletableFuture<>();
        GatewayMessage mockResponseMsg = new GatewayMessage();
        mockResponseMsg.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        mockResponseMsg.setRequestId(requestId);
        mockResponseMsg.setClientId(clientId);
        mockResponseMsg.setBody(largeContent);
        mockFuture.complete(mockResponseMsg);
        when(routeService.route(any())).thenReturn(mockFuture);

        // 1. 先发送认证消息
        testAuth();

        // 2. 发送业务消息
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        gatewayMessage.setRequestId(requestId);
        gatewayMessage.setClientId(clientId);

        CompletableFuture<GatewayMessage> bizMsgFuture = writeMsg(gatewayMessage);
        GatewayMessage responseMsg = bizMsgFuture.get();

        assertThat(responseMsg).isNotNull();
        assertThat(responseMsg.getClientId()).isEqualTo(gatewayMessage.getClientId());
        assertThat(responseMsg.getRequestId()).isEqualTo(gatewayMessage.getRequestId());
        assertThat(responseMsg.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_BIZ);
        assertThat(responseMsg.getBody()).isEqualTo(largeContent);
    }

    private Channel initClient() throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new GatewayMessageCodec());
                        pipeline.addLast(new SimpleChannelInboundHandler<GatewayMessage>() {
                            @Override
                            protected void channelRead0(
                                    ChannelHandlerContext ctx,
                                    GatewayMessage msg) throws Exception {
                                CompletableFuture<GatewayMessage> request =
                                        pendingRequests.remove(msg.getRequestId());
                                request.complete(msg);
                            }
                        });
                    }
                });
        Channel channel = bootstrap.connect(SERVER_HOST, port)
                .sync()
                .channel();
        channel.closeFuture()
                .addListener(future -> {
                    // 连接关闭，清除资源
                    pendingRequests.forEach((requestId, f) -> {
                        f.completeExceptionally(new ClosedChannelException());
                    });
                    pendingRequests.clear();
                });
        return channel;
    }

    private CompletableFuture<GatewayMessage> writeMsg(GatewayMessage request) {
        CompletableFuture<GatewayMessage> completableFuture = new CompletableFuture<>();
        clientChannel.writeAndFlush(request)
                .addListener(future -> {
                    if (future.isSuccess()) {
                        pendingRequests.put(request.getRequestId(), completableFuture);
                    }
                });
        return completableFuture;
    }

    private void doAuth() throws InterruptedException, ExecutionException {
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH);
        gatewayMessage.setRequestId(System.currentTimeMillis());
        gatewayMessage.setClientId(UUID.randomUUID()
                .toString());
        gatewayMessage.getExtensions()
                .put(DefaultAuthService.TOKEN_NAME, DefaultAuthService.TOKEN_VALUE);
        CompletableFuture<GatewayMessage> responseFuture = writeMsg(gatewayMessage);

        // 等待消息回来
        GatewayMessage response = responseFuture.get();

        assertThat(response).isNotNull();
        assertThat(response.getClientId()).isEqualTo(gatewayMessage.getClientId());
        assertThat(response.getRequestId()).isEqualTo(gatewayMessage.getRequestId());
        assertThat(response.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_AUTH_SUCCESS_RESP);
    }

}
