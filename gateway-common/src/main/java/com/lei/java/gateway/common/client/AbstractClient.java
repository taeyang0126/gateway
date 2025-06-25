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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Timer;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.route.ServiceInstance;

import static com.lei.java.gateway.common.constants.GatewayConstant.CONNECT_TIMEOUT_MILLIS;
import static com.lei.java.gateway.common.constants.GatewayConstant.MAX_RECONNECT_ATTEMPTS;

/**
 * <p>
 * AbstractClient
 * </p>
 *
 * @author 伍磊
 */
public abstract class AbstractClient<T extends AbstractClient<T>> implements Client<T> {
    private final Logger logger = LoggerFactory.getLogger(this.getClass()
            .getName());
    @Getter
    protected final ServiceInstance instance;
    protected final String host;
    protected final int port;
    protected final Bootstrap bootstrap;
    private final Timer timer;
    @Getter
    protected volatile Channel channel;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private int attempts;

    public AbstractClient(ServiceInstance instance, EventLoopGroup group, Timer timer) {
        this.instance = instance;
        this.host = instance.getHost();
        this.port = instance.getPort();
        this.timer = timer;
        this.bootstrap = new Bootstrap();

        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        // 让子类有机会添加自己的业务 Handler
                        initBusinessHandlers(pipeline);
                        // 在 Pipeline 的最后添加“看门狗”来监控连接状态
                        pipeline.addLast(new AbstractClient.ConnectionWatchdog());
                    }
                });
    }

    /**
     * 抽象方法，由子类实现，用于向 Pipeline 中添加自己的业务处理器。
     *
     * @param pipeline The channel pipeline
     */
    protected abstract void initBusinessHandlers(ChannelPipeline pipeline);

    @Override
    public void postProcessInActive() {
        // NO-OP
    }

    @SuppressWarnings("unchecked")
    @Override
    public void connect(CompletableFuture<T> connectionFuture) {
        if (shutdown.get()) {
            connectionFuture
                    .completeExceptionally(new IllegalStateException("Client has been shut down."));
            return;
        }

        if (isActive()) {
            connectionFuture.complete((T) this);
            return;
        }

        logger.info("尝试连接到 {}:{}...", host, port);
        ChannelFuture future = bootstrap.connect(host, port);

        future.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                connectSuccess(f);
                connectionFuture.complete((T) this);
            } else {
                // 连接失败，也需要完成 Future，但以异常方式
                connectionFuture.completeExceptionally(f.cause());
                connectFail(f);
            }
        });
    }

    @Override
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            logger.info("正在主动关闭到 {}:{} 的连接...", host, port);
            if (channel != null) {
                channel.close();
            }
            // Timer 和 EventLoopGroup 由 Manager 统一关闭，此处不处理
            logger.info("到 {}:{} 的客户端已关闭。", host, port);
        }
    }

    @Override
    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    private void connect() {
        if (shutdown.get() || channel != null && channel.isActive()) {
            return;
        }
        bootstrap.connect(host, port)
                .addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        connectSuccess(f);
                    } else {
                        connectFail(f);
                    }
                });
    }

    private void reconnect() {
        if (shutdown.get()) {
            return;
        }

        attempts++;
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            logger.error("连接 [{}:{}] 失败次数过多({}次)，放弃重连。", host, port, MAX_RECONNECT_ATTEMPTS);
            shutdown(); // 放弃重连，并关闭客户端
            return;
        }

        long delay = 2L << (attempts - 1); // 指数退避
        logger.info("连接 [{}:{}] 已断开，将在 {} 秒后尝试第 {} 次重连...", host, port, delay, attempts);
        timer.newTimeout(_ -> connect(), delay, TimeUnit.SECONDS);
    }

    private void resetAttempts() {
        this.attempts = 0;
    }

    private void connectSuccess(ChannelFuture f) {
        logger.info("成功连接到 {}:{}", host, port);
        this.channel = f.channel();
        // postProcessInActive
        postProcessInActive();
        resetAttempts();
    }

    private void connectFail(ChannelFuture f) {
        logger.info("连接到 {}:{} 失败。原因: ", host, port, f.cause());
        // 同时触发后台重连逻辑
        reconnect();
    }

    /**
     * 连接看门狗，负责在连接断开时发起重连。
     */
    public final class ConnectionWatchdog extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 连接成功激活后，也重置尝试次数
            resetAttempts();
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (shutdown.get()) {
                return;
            }
            // 已建立的连接断开，触发重连
            reconnect();
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("连接 [{}:{}] 发生异常: ", host, port, cause);
            ctx.close(); // 发生异常时，关闭连接，从而触发 channelInactive 进行重连
        }
    }
}
