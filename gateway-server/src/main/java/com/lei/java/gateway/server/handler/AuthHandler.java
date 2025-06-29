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

import java.util.function.BiConsumer;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.server.auth.AuthService;
import com.lei.java.gateway.server.domain.AuthResult;
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
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("AuthHandler");

    private final AuthService authService;
    private final SessionManager sessionManager;
    private final BiConsumer<ChannelFuture, Span> spanEndHandler;

    public AuthHandler(AuthService authService, SessionManager sessionManager) {
        this.authService = authService;
        this.sessionManager = sessionManager;
        this.spanEndHandler = (future, span) -> {
            if (!future.isSuccess()) {
                // 如果发送失败，记录异常
                span.recordException(future.cause());
                span.setStatus(StatusCode.ERROR, "Auth Response write failed");
            }
            span.end();
        };
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GatewayMessage msg) {
        Span span = TRACER.spanBuilder("auth")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("message.type", String.valueOf(msg.getMsgType()))
                .setAttribute("client.id", msg.getClientId())
                .setAttribute("request.id", String.valueOf(msg.getRequestId()))
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            AuthResult authResult = authService.authenticate(msg);
            if (!authResult.result()) {
                logger.info("channel authenticate failed, clientId={}", msg.getClientId());
                GatewayMessage response = new GatewayMessage();
                response.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH_FAIL_RESP);
                response.setRequestId(msg.getRequestId());
                response.setClientId(msg.getClientId());
                ctx.writeAndFlush(response)
                        .addListener((ChannelFutureListener) future -> spanEndHandler.accept(future,
                                span));
            } else {
                // 验证成功
                ctx.pipeline()
                        .remove(this);

                if (authResult.createSession()) {
                    // 构建 session
                    Session session =
                            sessionManager.createSession(msg.getClientId(), ctx.channel());
                    session.setAuthenticated(true);
                    logger.info("New session created: sessionId={}, clientId={}",
                            session.getId(),
                            msg.getClientId());
                }

                // response
                GatewayMessage response = new GatewayMessage();
                response.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH_SUCCESS_RESP);
                response.setRequestId(msg.getRequestId());
                response.setClientId(msg.getClientId());
                ctx.writeAndFlush(response)
                        .addListener((ChannelFutureListener) future -> spanEndHandler.accept(future,
                                span));
            }
        }
    }
}
