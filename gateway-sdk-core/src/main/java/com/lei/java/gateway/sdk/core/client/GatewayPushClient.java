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
package com.lei.java.gateway.sdk.core.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.client.AbstractClient;
import com.lei.java.gateway.common.codec.GatewayMessageCodec;
import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.sdk.core.domain.PushResult;
import com.lei.java.gateway.sdk.core.exception.MessagingException;
import com.lei.java.gateway.sdk.core.handler.GatewayPushMsgHandler;

import static com.lei.java.gateway.common.config.security.SecurityConfig.INNER_TOKEN_VALUE;
import static com.lei.java.gateway.common.config.security.SecurityConfig.TOKEN_NAME;

/**
 * <p>
 * GatewayClient
 * </p>
 *
 * @author 伍磊
 */
public class GatewayPushClient extends AbstractClient<GatewayPushClient> {
    private static final Logger logger = LoggerFactory.getLogger(GatewayPushClient.class);
    private final Map<Long, CompletableFuture<PushResult>> requests = new ConcurrentHashMap<>();

    public GatewayPushClient(ServiceInstance instance, EventLoopGroup group, Timer timer) {
        super(instance, group, timer);
    }

    public void send(
            Long requestId,
            String clientId,
            byte[] body,
            CompletableFuture<PushResult> completableFuture) {
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setClientId(clientId);
        gatewayMessage.setRequestId(requestId);
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_PUSH);
        gatewayMessage.setBody(body);
        channel.writeAndFlush(gatewayMessage)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            requests.put(gatewayMessage.getRequestId(), completableFuture);
                            logger.info("push msg success: {}", gatewayMessage.getRequestId());
                        } else {
                            completableFuture.completeExceptionally(
                                    new MessagingException("message push failed", future.cause()));
                        }
                    }
                });
    }

    public void pushSuccess(Long requestId) {
        CompletableFuture<PushResult> future = requests.remove(requestId);
        if (future != null) {
            future.complete(new PushResult(true));
        }
    }

    public void pushFail(Long requestId, String errorMsg) {
        CompletableFuture<PushResult> future = requests.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new MessagingException(errorMsg));
        }
    }

    @Override
    public void postProcessInActive() {
        // channel 连接成功
        // 发送 auth 消息
        GatewayMessage gatewayMessage = getAuthMessage();
        channel.writeAndFlush(gatewayMessage);
    }

    @Override
    protected void initBusinessHandlers(ChannelPipeline pipeline) {
        pipeline.addLast(new GatewayMessageCodec());
        pipeline.addLast(new GatewayPushMsgHandler(this));
    }

    @Override
    public void shutdown() {
        super.shutdown();
        clearResource();
    }

    private void clearResource() {
        requests.forEach((_, completeFuture) -> completeFuture
                .completeExceptionally(new RuntimeException("Connection closed")));
        requests.clear();
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
