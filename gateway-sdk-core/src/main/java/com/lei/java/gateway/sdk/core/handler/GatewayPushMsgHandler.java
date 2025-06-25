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
package com.lei.java.gateway.sdk.core.handler;

import java.util.UUID;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.sdk.core.client.GatewayPushClient;

/**
 * <p>
 * GatewayPushMsgHandler
 * </p>
 *
 * @author 伍磊
 */
public class GatewayPushMsgHandler extends SimpleChannelInboundHandler<GatewayMessage> {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayPushMsgHandler.class);
    private final GatewayPushClient gatewayPushClient;

    public GatewayPushMsgHandler(GatewayPushClient gatewayPushClient) {
        this.gatewayPushClient = gatewayPushClient;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GatewayMessage msg) throws Exception {
        byte msgType = msg.getMsgType();
        long requestId = msg.getRequestId();
        if (GatewayMessage.MESSAGE_TYPE_PUSH_SUCCESS == msgType) {
            gatewayPushClient.pushSuccess(requestId);
            return;
        }
        if (GatewayMessage.MESSAGE_TYPE_PUSH_FAIL == msgType) {
            byte[] body = msg.getBody();
            String errorMsg = "push message error";
            if (body != null) {
                errorMsg = new String(body);
            }
            gatewayPushClient.pushFail(requestId, errorMsg);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleStateEvent) {
            if (IdleState.WRITER_IDLE.equals(idleStateEvent.state())) {
                // 心跳
                GatewayMessage gatewayMessage = new GatewayMessage();
                gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_PUSH_HEARTBEAT);
                gatewayMessage.setRequestId(System.currentTimeMillis());
                gatewayMessage.setClientId(UUID.randomUUID()
                        .toString());
                ctx.channel()
                        .writeAndFlush(gatewayMessage);
                LOGGER.info("[push] heartbeat message send to server");
            }
        }
    }
}
