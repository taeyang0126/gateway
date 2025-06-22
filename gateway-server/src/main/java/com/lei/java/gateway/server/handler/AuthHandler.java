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
package com.lei.java.gateway.server.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.server.auth.AuthService;
import com.lei.java.gateway.server.session.Session;
import com.lei.java.gateway.server.session.SessionManager;

/**
 * <p>
 * 授权 handler
 * </p>
 *
 * @author 伍磊
 */
@ChannelHandler.Sharable
public class AuthHandler extends SimpleChannelInboundHandler<GatewayMessage> {

    private static final Logger logger = LoggerFactory.getLogger(AuthHandler.class);

    private final AuthService authService;
    private final SessionManager sessionManager;

    public AuthHandler(AuthService authService, SessionManager sessionManager) {
        this.authService = authService;
        this.sessionManager = sessionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GatewayMessage msg) {
        boolean authenticate = authService.authenticate(msg);
        if (!authenticate) {
            logger.info("channel authenticate failed, clientId={}", msg.getClientId());
            GatewayMessage response = new GatewayMessage();
            response.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH_FAIL_RESP);
            response.setRequestId(msg.getRequestId());
            response.setClientId(msg.getClientId());
            ctx.writeAndFlush(response);
        } else {
            // 验证成功
            ctx.pipeline()
                    .remove(this);

            // 构建 session
            Session session = sessionManager.createSession(msg.getClientId(), ctx.channel());
            session.setAuthenticated(true);
            logger.info("New session created: sessionId={}, clientId={}",
                    session.getId(),
                    msg.getClientId());

            // response
            GatewayMessage response = new GatewayMessage();
            response.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH_SUCCESS_RESP);
            response.setRequestId(msg.getRequestId());
            response.setClientId(msg.getClientId());
            ctx.writeAndFlush(response);
        }
    }
}
