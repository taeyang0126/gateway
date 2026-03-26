package com.lei.gateway.core.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 排空阶段处理器，在停机排空阶段拦截新到达的 HTTP 请求并返回 503。
 *
 * <p>Pipeline 位置：TraceContextHandler → DrainHandler → RoutingHandler。
 * 非排空状态下透传所有消息；排空状态下对 {@link HttpRequest} 返回
 * 503 + {@code Connection: close}，非 HttpRequest 消息直接释放。
 */
@ChannelHandler.Sharable
public class DrainHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(DrainHandler.class);

    private static final String BODY =
            "{\"status\":503,\"error\":\"Service Unavailable\","
                    + "\"message\":\"Server is shutting down\"}";

    private static final String HEALTH_LIVE_PATH = "/health/live";
    private static final String HEALTH_READY_PATH = "/health/ready";
    private static final String HEALTH_PATH = "/health";

    private volatile boolean draining;

    /** 激活排空模式。 */
    public void activateDrain() {
        this.draining = true;
    }

    /** 查询是否处于排空状态。 */
    public boolean isDraining() {
        return draining;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!draining) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (msg instanceof HttpRequest httpRequest) {
            // 健康检查端点在 drain 期间放行，由 RoutingHandler 处理
            String path = new QueryStringDecoder(httpRequest.uri()).path();
            if (HEALTH_LIVE_PATH.equals(path)
                    || HEALTH_READY_PATH.equals(path)
                    || HEALTH_PATH.equals(path)) {
                ctx.fireChannelRead(msg);
                return;
            }

            ByteBuf content = Unpooled.copiedBuffer(BODY, CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    content);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    .setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
                    .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response)
                    .addListener(future -> ctx.close());
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.warn("DrainHandler caught exception, closing channel", cause);
        ctx.close();
    }
}
