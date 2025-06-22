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
package com.lei.java.gateway.sdk.core.connection;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.sdk.core.exception.MessagingException;
import com.lei.java.gateway.sdk.core.handler.GatewayPushMsgHandler;

/**
 * <p>
 * GatewayClient
 * </p>
 *
 * @author 伍磊
 */
public class GatewayConnection {
    private static final Logger logger = LoggerFactory.getLogger(GatewayConnection.class);
    private final Channel channel;
    private final Map<Long, CompletableFuture<Void>> requests = new ConcurrentHashMap<>();

    public GatewayConnection(Channel channel) {
        this.channel = channel;
        this.channel.pipeline()
                .addLast(new GatewayPushMsgHandler(this));
    }

    public void send(String clientId, byte[] body, CompletableFuture<Void> completableFuture) {
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setClientId(clientId);
        gatewayMessage.setRequestId(System.currentTimeMillis());
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
        CompletableFuture<Void> future = requests.remove(requestId);
        if (future != null) {
            future.complete(null);
        }
    }

    public void pushFail(Long requestId, String errorMsg) {
        CompletableFuture<Void> future = requests.remove(requestId);
        if (future != null) {
            future.completeExceptionally(new MessagingException(errorMsg));
        }
    }
}
