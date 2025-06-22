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

import java.util.UUID;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.protocol.GatewayMessage;

/**
 * <p>
 * GatewayClientHandler
 * </p>
 *
 * @author 伍磊
 */
public class GatewayClientHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(GatewayClientHandler.class);
    private static final String TOKEN_NAME = "x-token";
    private static final String TOKEN_VALUE = "013dc334-9e05-43ee-b05a-c3e1c7d59407";

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // auth
        GatewayMessage gatewayMessage = new GatewayMessage();
        gatewayMessage.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH);
        gatewayMessage.setRequestId(System.currentTimeMillis());
        gatewayMessage.setClientId(UUID.randomUUID()
                .toString());
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
                gatewayMessage.setClientId(UUID.randomUUID()
                        .toString());
                ctx.channel()
                        .writeAndFlush(gatewayMessage);
                logger.info("heartbeat message send to server");
            }
        }
    }

}
