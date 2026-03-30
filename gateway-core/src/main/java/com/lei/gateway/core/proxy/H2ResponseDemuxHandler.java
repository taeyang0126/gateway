package com.lei.gateway.core.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * H2 响应分发器，安装在 H2 父 Channel pipeline 上。
 *
 * <p>非 {@code @Sharable}，每个 H2 连接创建一个独立实例。
 * 收到 H2 响应帧时根据 stream ID 查映射表找到对应的 {@link ProxyHandler} 并转发。
 * 同时跟踪 MAX_CONCURRENT_STREAMS 和活跃 stream 计数。
 */
public class H2ResponseDemuxHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(H2ResponseDemuxHandler.class);

    private final UpstreamConnectionPool connectionPool;

    /** streamId → ProxyHandler 映射表。 */
    private final ConcurrentHashMap<Integer, ProxyHandler> streamHandlers = new ConcurrentHashMap<>();

    /** 上游通告的 MAX_CONCURRENT_STREAMS，默认无限制。 */
    private volatile int maxConcurrentStreams = Integer.MAX_VALUE;

    /** 当前活跃 stream 数量。 */
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);

    /** 是否已收到 GOAWAY 帧。 */
    private volatile boolean goawayReceived;

    /** GOAWAY 帧中的 lastStreamId，默认 MAX_VALUE 表示未收到 GOAWAY。 */
    private volatile int lastStreamId = Integer.MAX_VALUE;

    /** 保存 ChannelHandlerContext，用于 remove() 中判断是否需要关闭连接。 */
    private ChannelHandlerContext handlerCtx;

    /** 创建 H2ResponseDemuxHandler。 */
    public H2ResponseDemuxHandler(UpstreamConnectionPool connectionPool) {
        this.connectionPool = connectionPool;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.handlerCtx = ctx;
    }

    /**
     * 检查是否可以在此连接上创建新 stream。
     *
     * @return 当前活跃 stream 数 < maxConcurrentStreams 时返回 true
     */
    public boolean canCreateStream() {
        return activeStreamCount.get() < maxConcurrentStreams;
    }

    /**
     * 原子性检查并预占一个 stream 槽位。
     *
     * <p>CAS 循环确保 check + increment 不可分割，消除
     * {@code canCreateStream()} 与 {@code incrementActiveStream()} 之间的竞态窗口。
     * 如果最终未能成功创建 stream（如 HEADERS 写失败），调用方必须调用
     * {@link #decrementActiveStream()} 归还槽位。
     *
     * @return true 表示成功预占，false 表示已达 maxConcurrentStreams 上限
     */
    public boolean tryReserveStream() {
        while (true) {
            int current = activeStreamCount.get();
            if (current >= maxConcurrentStreams) {
                return false;
            }
            if (activeStreamCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 预占一个 stream 槽位，递增活跃计数。
     *
     * @deprecated 使用 {@link #tryReserveStream()} 代替，避免 check-then-act 竞态。
     */
    @Deprecated
    public void incrementActiveStream() {
        activeStreamCount.incrementAndGet();
    }

    /**
     * 归还一个预占的 stream 槽位（未成功创建 stream 时的回退路径）。
     */
    public void decrementActiveStream() {
        activeStreamCount.decrementAndGet();
    }

    /**
     * 注册 streamId 到 ProxyHandler 的映射。
     *
     * <p>调用前必须已通过 {@link #incrementActiveStream()} 预占槽位。
     *
     * @param streamId H2 stream ID
     * @param handler  处理该 stream 响应的 ProxyHandler
     */
    public void register(int streamId, ProxyHandler handler) {
        streamHandlers.put(streamId, handler);
    }

    /**
     * 移除 streamId 映射，递减活跃计数。
     *
     * <p>如果已收到 GOAWAY 且映射表为空，关闭连接。
     *
     * @param streamId 要移除的 stream ID
     * @return 被移除的 ProxyHandler，不存在则返回 null
     */
    public ProxyHandler remove(int streamId) {
        ProxyHandler handler = streamHandlers.remove(streamId);
        if (handler != null) {
            int remaining = activeStreamCount.decrementAndGet();
            if (goawayReceived && remaining == 0 && handlerCtx != null) {
                handlerCtx.channel().close();
            }
        }
        return handler;
    }

    /**
     * 映射表是否有活跃 stream（停机时检查）。
     *
     * @return 有活跃 stream 返回 true
     */
    public boolean hasActiveStreams() {
        return activeStreamCount.get() > 0;
    }

    /**
     * 获取活跃 stream 数量。
     *
     * @return 活跃 stream 数
     */
    public int activeStreamCount() {
        return activeStreamCount.get();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Http2SettingsFrame settingsFrame) {
            handleSettings(settingsFrame);
            return;
        }
        if (msg instanceof Http2GoAwayFrame goAwayFrame) {
            handleGoAway(ctx, goAwayFrame);
            return;
        }
        if (msg instanceof Http2HeadersFrame headersFrame) {
            handleHeaders(headersFrame);
            return;
        }
        if (msg instanceof Http2DataFrame dataFrame) {
            handleData(dataFrame);
            return;
        }
        if (msg instanceof Http2ResetFrame resetFrame) {
            handleReset(resetFrame);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private void handleSettings(Http2SettingsFrame settingsFrame) {
        Long maxStreams = settingsFrame.settings().maxConcurrentStreams();
        if (maxStreams != null) {
            this.maxConcurrentStreams = maxStreams.intValue();
            log.info("更新 MAX_CONCURRENT_STREAMS: {}", this.maxConcurrentStreams);
        }
    }

    private void handleHeaders(Http2HeadersFrame headersFrame) {
        int streamId = headersFrame.stream().id();
        ProxyHandler handler = streamHandlers.get(streamId);
        if (handler == null) {
            log.debug("收到未知 streamId={} 的 HEADERS 帧，丢弃", streamId);
            return;
        }
        HttpResponse response = H2HeaderConverter.toH1Response(headersFrame.headers());
        handler.onH2Response(response);
        if (headersFrame.isEndStream()) {
            handler.onH2Content(LastHttpContent.EMPTY_LAST_CONTENT);
            remove(streamId);
        }
    }

    private void handleData(Http2DataFrame dataFrame) {
        int streamId = dataFrame.stream().id();
        ProxyHandler handler = streamHandlers.get(streamId);
        if (handler == null) {
            log.debug("收到未知 streamId={} 的 DATA 帧，丢弃", streamId);
            consumeFlowControlBytes(dataFrame);
            dataFrame.release();
            return;
        }
        ByteBuf content = dataFrame.content().retain();
        try {
            if (dataFrame.isEndStream()) {
                handler.onH2Content(new DefaultLastHttpContent(content));
                remove(streamId);
            } else {
                handler.onH2Content(new DefaultHttpContent(content));
            }
        } finally {
            consumeFlowControlBytes(dataFrame);
            dataFrame.release();
        }
    }

    /**
     * 消费 DATA 帧的 flow control 字节，触发 WINDOW_UPDATE 发送。
     *
     * <p>{@link io.netty.handler.codec.http2.Http2FrameCodec} 在收到 DATA 帧时记录了
     * {@code initialFlowControlledBytes}，下游 handler 必须通过写
     * {@link io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame} 来消费这些字节，
     * 否则 flow control 窗口不会更新，上游无法继续发送数据。
     */
    private void consumeFlowControlBytes(Http2DataFrame dataFrame) {
        int flowControlledBytes = dataFrame.initialFlowControlledBytes();
        if (flowControlledBytes > 0 && handlerCtx != null) {
            handlerCtx.channel().write(
                    new io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame(flowControlledBytes)
                            .stream(dataFrame.stream()));
            handlerCtx.channel().flush();
        }
    }

    private void handleReset(Http2ResetFrame resetFrame) {
        int streamId = resetFrame.stream().id();
        ProxyHandler handler = remove(streamId);
        if (handler != null) {
            handler.onH2Error(new H2StreamResetException(streamId, resetFrame.errorCode()));
        }
    }

    private void handleGoAway(ChannelHandlerContext ctx, Http2GoAwayFrame frame) {
        this.goawayReceived = true;
        this.lastStreamId = frame.lastStreamId();
        log.info("收到 GOAWAY: lastStreamId={}, errorCode={}", lastStreamId, frame.errorCode());

        // 从池中摘除但不关闭（存量 stream 继续处理）
        connectionPool.retire(ctx.channel());

        // streamId > lastStreamId 的 stream 触发 502
        int goAwayLastId = this.lastStreamId;
        streamHandlers.forEach((streamId, handler) -> {
            if (streamId > goAwayLastId) {
                handler.onH2Error(new H2GoAwayException(goAwayLastId, frame.errorCode()));
                remove(streamId);
            }
        });

        // 如果映射表已空，立即关闭
        if (activeStreamCount.get() == 0) {
            log.info("连接已移除 {} ", ctx.channel().attr(ChannelPoolEntry.POOL_ENTRY_KEY).get());
            ctx.channel().close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开：遍历映射表，对所有未完成的 stream 触发错误
        if (!streamHandlers.isEmpty()) {
            log.warn("H2 连接断开，清理 {} 个未完成的 stream", streamHandlers.size());
            H2ChannelClosedException cause = new H2ChannelClosedException();
            streamHandlers.forEach((streamId, handler) -> {
                handler.onH2Error(cause);
            });
            streamHandlers.clear();
            activeStreamCount.set(0);
        }
        ctx.fireChannelInactive();
    }



    /**
     * H2 stream 被 RST_STREAM 重置时抛出的异常。
     */
    public static class H2StreamResetException extends Exception {

        private final int streamId;
        private final long errorCode;

        /** 创建 H2StreamResetException。 */
        public H2StreamResetException(int streamId, long errorCode) {
            super("H2 stream " + streamId + " reset, errorCode=" + errorCode);
            this.streamId = streamId;
            this.errorCode = errorCode;
        }

        /** 返回被重置的 stream ID。 */
        public int getStreamId() {
            return streamId;
        }

        /** 返回 H2 错误码。 */
        public long getErrorCode() {
            return errorCode;
        }
    }

    /**
     * 收到 GOAWAY 帧时，streamId > lastStreamId 的 stream 抛出此异常。
     */
    public static class H2GoAwayException extends Exception {

        private final int goAwayLastStreamId;
        private final long errorCode;

        /** 创建 H2GoAwayException。 */
        public H2GoAwayException(int lastStreamId, long errorCode) {
            super("GOAWAY received, lastStreamId=" + lastStreamId + ", errorCode=" + errorCode);
            this.goAwayLastStreamId = lastStreamId;
            this.errorCode = errorCode;
        }

        /** 返回 GOAWAY 帧中的 lastStreamId。 */
        public int getGoAwayLastStreamId() {
            return goAwayLastStreamId;
        }

        /** 返回 H2 错误码。 */
        public long getErrorCode() {
            return errorCode;
        }
    }

    /**
     * H2 连接意外断开时抛出的异常。
     */
    public static class H2ChannelClosedException extends Exception {

        /** 创建 H2ChannelClosedException。 */
        public H2ChannelClosedException() {
            super("H2 connection closed unexpectedly");
        }
    }
}
