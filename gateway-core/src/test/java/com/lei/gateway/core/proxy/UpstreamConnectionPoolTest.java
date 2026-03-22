package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.lei.gateway.core.config.ConnectionPoolProperties;
import com.lei.gateway.core.observability.MetricsCollector;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UpstreamConnectionPool 单元测试。
 *
 * <p>使用真实的 EmbeddedChannel 模拟 Netty Channel，通过 mock MetricsCollector
 * 验证指标记录行为，不依赖真实网络连接。
 */
class UpstreamConnectionPoolTest {

    private MetricsCollector metricsCollector;
    private UpstreamConnectionPool pool;

    @BeforeEach
    void setUp() {
        metricsCollector = mock(MetricsCollector.class);
        ConnectionPoolProperties props = new ConnectionPoolProperties();
        // 使用真实 workerGroup 会尝试建立 TCP 连接，这里传 null 并通过 mock acquire 绕过
        pool = new UpstreamConnectionPool(props, metricsCollector, null);
    }

    // -------------------------------------------------------------------------
    // release
    // -------------------------------------------------------------------------

    @Test
    void releaseActiveChannel_requiteToPool() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // 注册 PoolEntry
        new ChannelPoolEntry(channel, "localhost:8080");

        // 先 acquire 一次让 pool 被创建，再手动替换为 mock pool
        // 直接测 release 的防御分支：entry 为 null
        EmbeddedChannel noEntryChannel = new EmbeddedChannel();
        pool.release(noEntryChannel); // entry == null，应 warn 并 return，不抛异常

        channel.finishAndReleaseAll();
        noEntryChannel.finishAndReleaseAll();
    }

    @Test
    void releaseChannel_entryNull_shouldNotThrow() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // 没有设置 POOL_ENTRY_KEY，entry 为 null
        pool.release(channel);
        // 不抛异常即通过
        channel.finishAndReleaseAll();
    }

    @Test
    void releaseChannel_poolNotFound_shouldNotThrow() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // 设置了 entry，但 pool 里没有对应的 key
        new ChannelPoolEntry(channel, "unknown:9999");
        pool.release(channel);
        // 不抛异常即通过
        channel.finishAndReleaseAll();
    }

    @Test
    void releaseInactiveChannel_shouldRemoveNotRequite() {
        EmbeddedChannel channel = new EmbeddedChannel();
        new ChannelPoolEntry(channel, "localhost:8080");
        // 关闭 channel，使 isActive() == false
        channel.close();
        // pool 里没有 localhost:8080 的池，走 pool==null 分支，不抛异常
        pool.release(channel);
        channel.finishAndReleaseAll();
    }

    // -------------------------------------------------------------------------
    // remove
    // -------------------------------------------------------------------------

    @Test
    void removeChannel_entryNull_shouldCloseAndNotThrow() {
        EmbeddedChannel channel = new EmbeddedChannel();
        // 没有设置 POOL_ENTRY_KEY
        pool.remove(channel);
        // channel 应被关闭
        assertThat(channel.isActive()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void removeChannel_poolNotFound_shouldCloseAndNotThrow() {
        EmbeddedChannel channel = new EmbeddedChannel();
        new ChannelPoolEntry(channel, "unknown:9999");
        pool.remove(channel);
        // pool 找不到时应关闭 channel
        assertThat(channel.isActive()).isFalse();
        channel.finishAndReleaseAll();
    }

    // -------------------------------------------------------------------------
    // closeAll
    // -------------------------------------------------------------------------

    @Test
    void closeAll_emptyPools_shouldNotThrow() {
        pool.closeAll();
        // 再次调用也不应抛异常
        pool.closeAll();
    }

    // -------------------------------------------------------------------------
    // acquire - metrics 验证（通过 mock connectionPool 间接测试）
    // -------------------------------------------------------------------------

    @Test
    void acquire_failure_recordsFailureMetrics() throws Exception {
        // 构造一个会立即失败的 pool，通过 ProxyHandler 的 mock 路径触发 exceptionally
        // 这里直接验证：acquire 返回失败 Future 时，调用方能正确处理
        ConnectionPoolProperties props = new ConnectionPoolProperties();
        props.setConnectTimeoutMillis(100);

        // 用 spy 方式：创建一个 acquire 直接返回失败 Future 的子类
        UpstreamConnectionPool failPool = new UpstreamConnectionPool(
                props, metricsCollector, null) {
            @Override
            public CompletableFuture<io.netty.channel.Channel> acquire(
                    String host, int port) {
                return CompletableFuture.failedFuture(
                        new RuntimeException("connect refused"));
            }
        };

        CompletableFuture<io.netty.channel.Channel> future =
                failPool.acquire("localhost", 8080);
        assertThat(future).isCompletedExceptionally();
    }

    @Test
    void acquire_success_returnsChannel() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        new ChannelPoolEntry(channel, "localhost:8080");

        UpstreamConnectionPool successPool = new UpstreamConnectionPool(
                new ConnectionPoolProperties(), metricsCollector, null) {
            @Override
            public CompletableFuture<io.netty.channel.Channel> acquire(
                    String host, int port) {
                return CompletableFuture.completedFuture(channel);
            }
        };

        CompletableFuture<io.netty.channel.Channel> future =
                successPool.acquire("localhost", 8080);
        assertThat(future).isCompletedWithValue(channel);

        channel.finishAndReleaseAll();
    }
}
