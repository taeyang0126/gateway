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
package com.lei.java.gateway.client.example;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.protocol.GatewayMessage;

import static com.lei.java.gateway.common.config.security.SecurityConfig.TOKEN_NAME;
import static com.lei.java.gateway.common.config.security.SecurityConfig.TOKEN_VALUE;

/**
 * <p>
 * GatewayClientHandler
 * </p>
 *
 * @author 伍磊
 */
public class GatewayClientHandler extends SimpleChannelInboundHandler<GatewayMessage> {
    private static final Logger logger = LoggerFactory.getLogger(GatewayClientHandler.class);
    private static final String CLIENT_ID = "gateway-client-example";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GatewayMessage msg) throws Exception {
        if (msg.getMsgType() == GatewayMessage.MESSAGE_TYPE_PUSH) {
            logger.info("received push message: {}", new String(msg.getBody()));
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // auth
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH);
        gatewayMessage.setRequestId(System.currentTimeMillis());
        gatewayMessage.setClientId(CLIENT_ID);
        gatewayMessage.getExtensions()
                .put(TOKEN_NAME, TOKEN_VALUE);
        ctx.channel()
                .writeAndFlush(gatewayMessage);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleStateEvent) {
            if (IdleState.WRITER_IDLE.equals(idleStateEvent.state())) {
                // 心跳
                GatewayMessage gatewayMessage = new GatewayMessage();
                gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_HEARTBEAT);
                gatewayMessage.setRequestId(System.currentTimeMillis());
                gatewayMessage.setClientId(CLIENT_ID);
                ctx.channel()
                        .writeAndFlush(gatewayMessage);
                logger.info("heartbeat message send to server");
            }
        }
    }
}
