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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadFactory;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.server.route.RouteService;
import com.lei.java.gateway.server.session.DefaultSession;
import com.lei.java.gateway.server.session.Session;
import com.lei.java.gateway.server.session.SessionManager;

/**
 * 网关服务器消息处理器
 */
public class GatewayServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(GatewayServerHandler.class);

    private final SessionManager sessionManager;
    private final RouteService routeService;
    private final ThreadFactory businessFactory;
    private final ThreadFactory pushFactory;
    private ChannelHandlerContext ctx;

    public GatewayServerHandler(SessionManager sessionManager, RouteService routeService) {
        this.sessionManager = sessionManager;
        this.routeService = routeService;
        this.businessFactory = Thread.ofVirtual()
                .name("business-handler-", 0)
                .uncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception", e))
                .factory();
        this.pushFactory = Thread.ofVirtual()
                .name("push-handler-", 0)
                .uncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception", e))
                .factory();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof GatewayMessage message)) {
            ReferenceCountUtil.release(msg);
            logger.error("Received message is not GatewayMessage: {}", msg);
            return;
        }

        Session session = getSession(message);

        try {
            // 处理不同类型的消息
            switch (message.getMsgType()) {
                case GatewayMessage.MESSAGE_TYPE_HEARTBEAT:
                    // 心跳消息直接在 EventLoop 中处理，因为处理逻辑简单
                    handleHeartbeat(message, session);
                    break;
                case GatewayMessage.MESSAGE_TYPE_BIZ:
                    // 业务消息提交到业务线程池处理
                    final GatewayMessage requestMessage = message;
                    final Session currentSession = session;
                    businessFactory.newThread(() -> {
                        try {
                            handleBizMessage(requestMessage, currentSession);
                        } catch (Exception e) {
                            logger.error("Handle business message error", e);
                            handleError(requestMessage, e);
                        }
                    })
                            .start();
                    break;
                case GatewayMessage.MESSAGE_TYPE_PUSH:
                    // 推送消息
                    pushFactory.newThread(() -> handlerPushMsg(message))
                            .start();
                    break;
                case GatewayMessage.MESSAGE_TYPE_PUSH_HEARTBEAT:
                    // 推送心跳消息，不做任何事情
                    break;
                default:
                    logger.warn("Unknown message type: {}", message.getMsgType());
                    handleError(message, new IllegalArgumentException("Unknown message type"));
            }
        } catch (Exception e) {
            logger.error("Handle message error", e);
            handleError(message, e);
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开时清理会话
        Session session = DefaultSession.getSession(ctx.channel());
        if (session != null) {
            sessionManager.removeSession(session.getId());
            logger.info("Client disconnected, session removed: {}", session.getId());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                // 读空闲，关闭连接
                Session session = DefaultSession.getSession(ctx.channel());
                if (session != null) {
                    logger.warn("Channel idle, closing session: {}", session.getId());
                    sessionManager.removeSession(session.getId());
                }
                ctx.close();
            }
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel exception caught", cause);
        Session session = DefaultSession.getSession(ctx.channel());
        if (session != null) {
            sessionManager.removeSession(session.getId());
        }
        ctx.close();
    }

    private Session getSession(GatewayMessage message) {
        // 推送消息不需要建立 session
        if (message.getMsgType() == GatewayMessage.MESSAGE_TYPE_PUSH) {
            return null;
        }
        Session session = DefaultSession.getSession(ctx.channel());
        if (session == null) {
            ctx.close();
        }
        return session;
    }

    private void handleHeartbeat(GatewayMessage message, Session session) {
        if (session == null) {
            logger.warn("Unknown heartbeat message received");
            ctx.close();
            return;
        }
        if (!session.isAuthenticated()) {
            logger.warn("Unauthorized heartbeat message received");
            sessionManager.removeSession(session.getId());
            return;
        }

        // 更新最后活跃时间
        sessionManager.updateLastActiveTime(session.getId());

        // 响应心跳
        GatewayMessage response = new GatewayMessage();
        response.setMsgType(GatewayMessage.MESSAGE_TYPE_HEARTBEAT);
        response.setRequestId(message.getRequestId());
        response.setClientId(message.getClientId());
        ctx.writeAndFlush(response);
    }

    private void handleBizMessage(GatewayMessage message, Session session) {
        if (session == null) {
            logger.warn("UnKnown business message received");
            ctx.close();
            return;
        }
        if (!session.isAuthenticated()) {
            logger.warn("Unauthorized business message received");
            sessionManager.removeSession(session.getId());
            return;
        }

        // 更新最后活跃时间
        session.updateLastActiveTime();

        // 实现业务消息路由转发逻辑
        routeService.route(message)
                .whenComplete((response, ex) -> {
                    if (ex != null) {
                        logger.error("Failed to route message={}, e: ", message, ex);
                        handleError(message, ex);
                    } else {
                        ctx.writeAndFlush(response);
                    }
                });
    }

    private void handlerPushMsg(GatewayMessage message) {
        long requestId = message.getRequestId();
        String clientId = message.getClientId();
        Session session = sessionManager.getSessionByClientId(clientId);
        GatewayMessage response = new GatewayMessage();
        response.setClientId(clientId);
        response.setRequestId(requestId);
        if (session == null) {
            response.setMsgType(GatewayMessage.MESSAGE_TYPE_PUSH_FAIL);
            response.setBody("client not found or offline".getBytes(StandardCharsets.UTF_8));
            ctx.writeAndFlush(response);
            return;
        }

        // 推送给客户
        // todo-wl 当前只是发送出去就算推送成功，没有做确认
        session.getChannel()
                .writeAndFlush(message)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            response.setMsgType(GatewayMessage.MESSAGE_TYPE_PUSH_SUCCESS);
                            logger.info("Push message success: {}", clientId);
                            ctx.writeAndFlush(response);
                        } else {
                            response.setMsgType(GatewayMessage.MESSAGE_TYPE_PUSH_FAIL);
                            response.setBody(("client message push failed: "
                                    + future.cause()
                                            .getMessage())
                                    .getBytes(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(response);
                        }
                    }
                });
    }

    private void handleError(GatewayMessage message, Throwable cause) {
        GatewayMessage response = new GatewayMessage();
        response.setMsgType(GatewayMessage.MESSAGE_TYPE_ERROR);
        response.setRequestId(message.getRequestId());
        response.setClientId(message.getClientId());
        String errorMsg = cause.getMessage();
        // TODO 异常优化下
        if (errorMsg != null) {
            response.setBody(errorMsg.getBytes(StandardCharsets.UTF_8));
        } else {
            response.setBody("system is busy".getBytes(StandardCharsets.UTF_8));
        }
        ctx.writeAndFlush(response);
    }
}