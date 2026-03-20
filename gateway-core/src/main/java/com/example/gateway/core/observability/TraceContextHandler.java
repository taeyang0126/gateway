package com.example.gateway.core.observability;

import com.example.gateway.core.config.ObservabilityProperties;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context 解析和生成处理器。
 *
 * <p>解析请求中的 {@code traceparent} 头，提取 trace-id 和 span-id，
 * 生成新的 span-id 存入 Channel Attribute。未携带时生成新的 trace-id 和 span-id。
 *
 * <p>traceparent 格式（W3C Trace Context Level 1）：
 * {@code version-traceid-parentid-traceflags}，例如
 * {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}
 */
@ChannelHandler.Sharable
public class TraceContextHandler extends ChannelInboundHandlerAdapter {

    /** Channel Attribute key：存储转发给 upstream 的 traceparent 值。 */
    public static final AttributeKey<String> TRACEPARENT_KEY =
            AttributeKey.valueOf("traceparent");

    /** Channel Attribute key：存储原始 tracestate 值（原样传递）。 */
    public static final AttributeKey<String> TRACESTATE_KEY =
            AttributeKey.valueOf("tracestate");

    /** Channel Attribute key：存储 trace-id（供访问日志使用）。 */
    public static final AttributeKey<String> TRACE_ID_KEY =
            AttributeKey.valueOf("traceId");

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String TRACESTATE_HEADER = "tracestate";

    /**
     * traceparent 正则：version(2hex)-traceid(32hex)-parentid(16hex)-flags(2hex)。
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObservabilityProperties config;

    /** 创建 TraceContextHandler。 */
    public TraceContextHandler(ObservabilityProperties config) {
        this.config = config;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!config.isTracingEnabled() || !(msg instanceof HttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        String traceparent = request.headers().get(TRACEPARENT_HEADER);
        String tracestate = request.headers().get(TRACESTATE_HEADER);

        String traceId;
        String newTraceparent;

        if (traceparent != null) {
            Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparent);
            if (matcher.matches()) {
                String version = matcher.group(1);
                traceId = matcher.group(2);
                String flags = matcher.group(4);
                String newSpanId = generateSpanId();
                newTraceparent = version + "-" + traceId + "-" + newSpanId + "-" + flags;
            } else {
                // 非法格式，生成全新的 trace context
                traceId = generateTraceId();
                String spanId = generateSpanId();
                newTraceparent = "00-" + traceId + "-" + spanId + "-01";
                tracestate = null;
            }
        } else {
            traceId = generateTraceId();
            String spanId = generateSpanId();
            newTraceparent = "00-" + traceId + "-" + spanId + "-01";
        }

        ctx.channel().attr(TRACEPARENT_KEY).set(newTraceparent);
        ctx.channel().attr(TRACE_ID_KEY).set(traceId);
        if (tracestate != null) {
            ctx.channel().attr(TRACESTATE_KEY).set(tracestate);
        } else {
            ctx.channel().attr(TRACESTATE_KEY).set(null);
        }

        ctx.fireChannelRead(msg);
    }

    /** 生成 16 字节（32 hex 字符）的 trace-id。 */
    private static String generateTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytesToHex(bytes);
    }

    /** 生成 8 字节（16 hex 字符）的 span-id。 */
    private static String generateSpanId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return bytesToHex(bytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
