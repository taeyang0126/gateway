package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Settings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class H2ResponseDemuxHandlerPropertyTest {

    private H2ResponseDemuxHandler createHandler() {
        UpstreamConnectionPool pool = mock(UpstreamConnectionPool.class);
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        // EmbeddedChannel 会触发 handlerAdded，初始化 handlerCtx
        new EmbeddedChannel(handler);
        return handler;
    }

    private ProxyHandler mockProxyHandler() {
        return mock(ProxyHandler.class);
    }

    private List<Integer> generateStreamIds(int count) {
        List<Integer> ids = new ArrayList<>(count);
        for (int ii = 0; ii < count; ii++) {
            ids.add(ii * 2 + 1);
        }
        return ids;
    }

    /**
     * 构造一个可设置 lastStreamId 的 mock Http2GoAwayFrame。
     * Netty 4.2 的 DefaultHttp2GoAwayFrame 不允许外部设置 lastStreamId，
     * 只有 codec 内部才会设置，因此测试中需要 mock。
     */
    private Http2GoAwayFrame mockGoAwayFrame(int lastStreamId, long errorCode) {
        Http2GoAwayFrame frame = mock(Http2GoAwayFrame.class);
        when(frame.lastStreamId()).thenReturn(lastStreamId);
        when(frame.errorCode()).thenReturn(errorCode);
        when(frame.content()).thenReturn(Unpooled.EMPTY_BUFFER);
        return frame;
    }

    // ========================================================================
    // Feature: upstream-h2-connection, Property 2: Stream 生命周期——注册、路由、移除与流控计数
    // Validates: Requirements 3.5, 3.6, 4.1, 4.2
    // ========================================================================

    /**
     * 注册 N 个 stream 后 activeStreamCount == N，逐个 remove 后计数递减至 0。
     * 每次 remove 返回注册时的 ProxyHandler。
     */
    @Property(tries = 100)
    void registerAndRemoveKeepsCountConsistent(
            @ForAll @IntRange(min = 1, max = 200) int streamCount) {

        H2ResponseDemuxHandler handler = createHandler();
        List<Integer> ids = generateStreamIds(streamCount);
        Map<Integer, ProxyHandler> registered = new LinkedHashMap<>();

        for (int id : ids) {
            ProxyHandler ph = mockProxyHandler();
            handler.register(id, ph);
            registered.put(id, ph);
            assertThat(handler.activeStreamCount())
                    .as("注册 %d 个 stream 后 activeStreamCount", registered.size())
                    .isEqualTo(registered.size());
        }

        assertThat(handler.hasActiveStreams()).isTrue();

        int remaining = streamCount;
        for (var entry : registered.entrySet()) {
            ProxyHandler removed = handler.remove(entry.getKey());
            remaining--;
            assertThat(removed)
                    .as("remove(%d) 应返回注册时的 ProxyHandler", entry.getKey())
                    .isSameAs(entry.getValue());
            assertThat(handler.activeStreamCount()).isEqualTo(remaining);
        }

        assertThat(handler.hasActiveStreams()).isFalse();
        assertThat(handler.activeStreamCount()).isZero();
    }

    /**
     * 重复 remove 同一个 streamId，第二次返回 null 且计数不会多减。
     */
    @Property(tries = 100)
    void duplicateRemoveReturnsNullWithoutExtraDecrement(
            @ForAll @IntRange(min = 1, max = 100) int streamCount) {

        H2ResponseDemuxHandler handler = createHandler();
        List<Integer> ids = generateStreamIds(streamCount);

        for (int id : ids) {
            handler.register(id, mockProxyHandler());
        }

        for (int id : ids) {
            assertThat(handler.remove(id)).isNotNull();
        }
        assertThat(handler.activeStreamCount()).isZero();

        for (int id : ids) {
            assertThat(handler.remove(id)).isNull();
        }
        assertThat(handler.activeStreamCount()).isZero();
    }

    /**
     * canCreateStream() 在 activeStreamCount < maxConcurrentStreams 时返回 true，否则 false。
     */
    @Property(tries = 100)
    void canCreateStreamRespectsMaxConcurrentStreams(
            @ForAll @IntRange(min = 1, max = 50) int maxStreams,
            @ForAll @IntRange(min = 1, max = 100) int registerCount) {

        UpstreamConnectionPool pool = mock(UpstreamConnectionPool.class);
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        Http2Settings settings = new Http2Settings();
        settings.maxConcurrentStreams(maxStreams);
        channel.writeInbound(new DefaultHttp2SettingsFrame(settings));

        for (int ii = 0; ii < registerCount; ii++) {
            boolean expectedCanCreate = ii < maxStreams;
            assertThat(handler.canCreateStream())
                    .as("注册 %d 个 stream 后 canCreateStream (max=%d)", ii, maxStreams)
                    .isEqualTo(expectedCanCreate);
            handler.register(ii * 2 + 1, mockProxyHandler());
        }

        // 移除足够多的 stream 使 activeStreamCount 降到 maxStreams 以下
        if (registerCount >= maxStreams) {
            for (int ii = 0; ii < registerCount; ii++) {
                handler.remove(ii * 2 + 1);
            }
            assertThat(handler.activeStreamCount()).isZero();
            assertThat(handler.canCreateStream()).isTrue();
        }

        channel.finishAndReleaseAll();
    }

    /**
     * 并发注册和移除后 activeStreamCount 最终归零，不会出现负数或残留。
     */
    @Property(tries = 100)
    void concurrentRegisterAndRemoveKeepsCountConsistent(
            @ForAll @IntRange(min = 2, max = 8) int threadCount,
            @ForAll @IntRange(min = 1, max = 50) int idsPerThread)
            throws Exception {

        H2ResponseDemuxHandler handler = createHandler();
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger nextId = new AtomicInteger(1);
        Set<Integer> allRegistered = ConcurrentHashMap.newKeySet();

        try (ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threadCount, threadCount,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>())) {

            List<Future<?>> futures = new ArrayList<>(threadCount);
            for (int th = 0; th < threadCount; th++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    for (int ii = 0; ii < idsPerThread; ii++) {
                        int id = nextId.getAndAdd(2);
                        handler.register(id, mockProxyHandler());
                        allRegistered.add(id);
                    }
                }));
            }
            for (Future<?> fu : futures) {
                fu.get(10, TimeUnit.SECONDS);
            }
        }

        int totalRegistered = allRegistered.size();
        assertThat(handler.activeStreamCount()).isEqualTo(totalRegistered);

        // 并发移除
        CyclicBarrier barrier2 = new CyclicBarrier(threadCount);
        List<Integer> idList = new ArrayList<>(allRegistered);
        AtomicInteger removeIdx = new AtomicInteger(0);

        try (ThreadPoolExecutor executor2 = new ThreadPoolExecutor(
                threadCount, threadCount,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>())) {

            List<Future<?>> futures2 = new ArrayList<>(threadCount);
            for (int th = 0; th < threadCount; th++) {
                futures2.add(executor2.submit(() -> {
                    try {
                        barrier2.await(5, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    int idx;
                    while ((idx = removeIdx.getAndIncrement()) < idList.size()) {
                        handler.remove(idList.get(idx));
                    }
                }));
            }
            for (Future<?> fu : futures2) {
                fu.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(handler.activeStreamCount()).isZero();
        assertThat(handler.hasActiveStreams()).isFalse();
    }

    // ========================================================================
    // Feature: upstream-h2-connection, Property 5: 连接断开时映射表全清理
    // Validates: Requirements 6.2
    // ========================================================================

    /**
     * channelInactive 后 activeStreamCount == 0，
     * 每个已注册的 ProxyHandler 都收到 onH2Error(H2ChannelClosedException)。
     */
    @Property(tries = 100)
    void channelInactiveClearsAllStreamsAndNotifiesHandlers(
            @ForAll @IntRange(min = 0, max = 200) int streamCount) {

        UpstreamConnectionPool pool = mock(UpstreamConnectionPool.class);
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        List<ProxyHandler> handlers = new ArrayList<>(streamCount);
        for (int ii = 0; ii < streamCount; ii++) {
            ProxyHandler ph = mockProxyHandler();
            handler.register(ii * 2 + 1, ph);
            handlers.add(ph);
        }

        assertThat(handler.activeStreamCount()).isEqualTo(streamCount);

        channel.close();

        assertThat(handler.activeStreamCount()).isZero();
        assertThat(handler.hasActiveStreams()).isFalse();

        for (ProxyHandler ph : handlers) {
            verify(ph).onH2Error(any(H2ResponseDemuxHandler.H2ChannelClosedException.class));
        }

        // remove 任何 streamId 都应返回 null
        for (int ii = 0; ii < streamCount; ii++) {
            assertThat(handler.remove(ii * 2 + 1)).isNull();
        }
    }

    /**
     * 空映射表时 channelInactive 不抛异常，activeStreamCount 保持 0。
     */
    @Property(tries = 100)
    void channelInactiveOnEmptyMapIsNoOp(
            @ForAll @IntRange(min = 0, max = 10) int dummy) {

        UpstreamConnectionPool pool = mock(UpstreamConnectionPool.class);
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertThat(handler.activeStreamCount()).isZero();
        channel.close();
        assertThat(handler.activeStreamCount()).isZero();
    }

    // ========================================================================
    // Feature: upstream-h2-connection, Property 6: GOAWAY 后 streamId 分区处理
    // Validates: Requirements 6.1
    // ========================================================================

    /**
     * 收到 GOAWAY(lastStreamId=K) 后：
     * - streamId ≤ K 的 stream 不受影响（仍在映射表中）
     * - streamId > K 的 stream 全部收到 onH2Error(H2GoAwayException) 并从映射表移除
     * - retire 被调用一次
     */
    @Property(tries = 100)
    void goAwayPartitionsStreamsByLastStreamId(
            @ForAll @IntRange(min = 2, max = 100) int streamCount,
            @ForAll @IntRange(min = 1, max = 99) int lastStreamIdIndex) {

        int effectiveIndex = Math.min(lastStreamIdIndex, streamCount - 1);

        UpstreamConnectionPool pool = mock(UpstreamConnectionPool.class);
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        List<Integer> ids = generateStreamIds(streamCount);
        Map<Integer, ProxyHandler> registered = new LinkedHashMap<>();

        for (int id : ids) {
            ProxyHandler ph = mockProxyHandler();
            handler.register(id, ph);
            registered.put(id, ph);
        }

        int lastStreamId = ids.get(effectiveIndex);

        // 通过 pipeline 触发 channelRead，模拟收到 GOAWAY
        handler.channelRead(channel.pipeline().context(handler), mockGoAwayFrame(lastStreamId, 0));

        int survivorCount = 0;
        int evictedCount = 0;
        for (var entry : registered.entrySet()) {
            int streamId = entry.getKey();
            ProxyHandler ph = entry.getValue();
            if (streamId <= lastStreamId) {
                verify(ph, never()).onH2Error(any());
                assertThat(handler.remove(streamId))
                        .as("streamId=%d ≤ lastStreamId=%d 应仍在映射表中", streamId, lastStreamId)
                        .isSameAs(ph);
                survivorCount++;
            } else {
                verify(ph).onH2Error(any(H2ResponseDemuxHandler.H2GoAwayException.class));
                assertThat(handler.remove(streamId))
                        .as("streamId=%d > lastStreamId=%d 应已从映射表移除", streamId, lastStreamId)
                        .isNull();
                evictedCount++;
            }
        }

        assertThat(survivorCount).isGreaterThan(0);
        if (effectiveIndex < streamCount - 1) {
            assertThat(evictedCount).isGreaterThan(0);
        }

        verify(pool).retire(channel);

        channel.finishAndReleaseAll();
    }

    /**
     * GOAWAY lastStreamId 大于所有已注册 streamId 时，所有 stream 都存活。
     */
    @Property(tries = 100)
    void goAwayWithHighLastStreamIdKeepsAllStreams(
            @ForAll @IntRange(min = 1, max = 100) int streamCount) {

        UpstreamConnectionPool pool = mock(UpstreamConnectionPool.class);
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        List<Integer> ids = generateStreamIds(streamCount);
        List<ProxyHandler> handlers = new ArrayList<>(streamCount);

        for (int id : ids) {
            ProxyHandler ph = mockProxyHandler();
            handler.register(id, ph);
            handlers.add(ph);
        }

        int maxId = ids.get(ids.size() - 1);
        handler.channelRead(channel.pipeline().context(handler), mockGoAwayFrame(maxId + 2, 0));

        for (ProxyHandler ph : handlers) {
            verify(ph, never()).onH2Error(any());
        }

        assertThat(handler.activeStreamCount()).isEqualTo(streamCount);
        verify(pool).retire(channel);

        channel.finishAndReleaseAll();
    }

    /**
     * GOAWAY lastStreamId=0 时，所有 stream 都被驱逐（客户端 streamId 从 1 开始，全部 > 0）。
     */
    @Property(tries = 100)
    void goAwayWithZeroLastStreamIdEvictsAllStreams(
            @ForAll @IntRange(min = 1, max = 100) int streamCount) {

        UpstreamConnectionPool pool = mock(UpstreamConnectionPool.class);
        H2ResponseDemuxHandler handler = new H2ResponseDemuxHandler(pool);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        List<ProxyHandler> handlers = new ArrayList<>(streamCount);
        for (int ii = 0; ii < streamCount; ii++) {
            ProxyHandler ph = mockProxyHandler();
            handler.register(ii * 2 + 1, ph);
            handlers.add(ph);
        }

        handler.channelRead(channel.pipeline().context(handler), mockGoAwayFrame(0, 0));

        for (ProxyHandler ph : handlers) {
            verify(ph).onH2Error(any(H2ResponseDemuxHandler.H2GoAwayException.class));
        }

        assertThat(handler.activeStreamCount()).isZero();
        verify(pool).retire(channel);

        channel.finishAndReleaseAll();
    }
}
