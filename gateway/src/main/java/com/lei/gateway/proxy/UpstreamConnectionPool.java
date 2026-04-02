package com.lei.gateway.proxy;

import com.lei.gateway.config.ConnectionPoolProperties;
import com.lei.gateway.observability.MetricsCollector;
import com.lei.gateway.pool.ConcurrentPool;
import com.lei.gateway.pool.PoolConfig;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按 upstream host:port 分组的连接池管理器。
 *
 * <p>每组对应一个 {@link ConcurrentPool}{@code <ChannelPoolEntry>} 实例。
 */
@Component
public class UpstreamConnectionPool {

    private static final Logger log =
            LoggerFactory.getLogger(UpstreamConnectionPool.class);

    private final ConcurrentHashMap<String, ConcurrentPool<ChannelPoolEntry>> pools =
            new ConcurrentHashMap<>();
    private final ConnectionPoolProperties properties;
    private final MetricsCollector metricsCollector;
    private final EventLoopGroup workerGroup;

    /** 创建 UpstreamConnectionPool。 */
    public UpstreamConnectionPool(ConnectionPoolProperties properties,
            MetricsCollector metricsCollector,
            EventLoopGroup workerGroup) {
        this.properties = properties;
        this.metricsCollector = metricsCollector;
        this.workerGroup = workerGroup;
    }

    /**
     * 异步获取到指定 upstream 的连接。
     *
     * @param host upstream 主机
     * @param port upstream 端口
     * @return 包含可用 Netty Channel 的 CompletableFuture
     */
    public CompletableFuture<Channel> acquire(String host, int port) {
        String key = host + ":" + port;
        ConcurrentPool<ChannelPoolEntry> pool = pools.computeIfAbsent(key, k -> {
            PoolConfig poolConfig = toPoolConfig();
            ChannelPoolEntryFactory factory = new ChannelPoolEntryFactory(
                    host, port, workerGroup, NioSocketChannel.class,
                    properties.getConnectTimeoutMillis(), this);
            ConcurrentPool<ChannelPoolEntry> newPool =
                    new ConcurrentPool<>(poolConfig, factory);
            metricsCollector.registerPoolMetrics(k, newPool);
            return newPool;
        });

        long startNanos = System.nanoTime();
        return pool.borrowAsync(
                properties.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS)
                .thenApply(entry -> {
                    long durationNanos = System.nanoTime() - startNanos;
                    metricsCollector.recordUpstreamConnect(key, durationNanos,
                            true, properties.getSlowConnectThresholdMillis());
                    return entry.getChannel();
                })
                .exceptionally(e -> {
                    long durationNanos = System.nanoTime() - startNanos;
                    metricsCollector.recordUpstreamConnect(key, durationNanos,
                            false, properties.getSlowConnectThresholdMillis());
                    metricsCollector.recordPoolBorrowFailure(key);
                    throw e instanceof CompletionException
                            ? (CompletionException) e
                            : new CompletionException(e);
                });
    }

    /**
     * 归还连接到池中。如果 channel 已不活跃则移除。
     *
     * <p>通过 Channel Attribute 反查关联的 {@link ChannelPoolEntry}。
     *
     * @param channel 要归还的 Netty Channel
     */
    public void release(Channel channel) {
        ChannelPoolEntry entry = channel.attr(ChannelPoolEntry.POOL_ENTRY_KEY).get();
        if (entry == null) {
            log.warn("释放连接时未找到关联的 PoolEntry: {}", channel);
            return;
        }
        String key = entry.getPoolKey();
        ConcurrentPool<ChannelPoolEntry> pool = pools.get(key);
        if (pool == null) {
            log.warn("释放连接时未找到对应的连接池: {}", key);
            return;
        }
        if (channel.isActive()) {
            pool.requite(entry);
        } else {
            pool.remove(entry);
        }
    }

    /**
     * 强制从连接池中移除并关闭连接，不归还。
     *
     * <p>用于请求未正常完成时（如 client 断开、异常），此时 upstream channel
     * 处于中间状态，不能归还给连接池复用。
     *
     * @param channel 要移除的 Netty Channel
     */
    public void remove(Channel channel) {
        ChannelPoolEntry entry = channel.attr(ChannelPoolEntry.POOL_ENTRY_KEY).get();
        if (entry == null) {
            log.warn("移除连接时未找到关联的 PoolEntry: {}", channel);
            channel.close();
            return;
        }
        String key = entry.getPoolKey();
        ConcurrentPool<ChannelPoolEntry> pool = pools.get(key);
        if (pool == null) {
            log.warn("移除连接时未找到对应的连接池: {}", key);
            channel.close();
            return;
        }
        pool.remove(entry);
    }

    /**
     * 从池中摘除连接但不关闭，由调用方决定关闭时机。
     *
     * <p>用于 GOAWAY 和 streamId 溢出等场景：连接上可能还有正在飞行的 stream，
     * 不能立即关闭，但需要从池中摘除防止新请求使用。
     *
     * @param channel 要退役的 Netty Channel
     */
    public void retire(Channel channel) {
        ChannelPoolEntry entry = channel.attr(ChannelPoolEntry.POOL_ENTRY_KEY).get();
        if (entry == null) {
            log.warn("退役连接时未找到关联的 PoolEntry: {}", channel);
            return;
        }
        String key = entry.getPoolKey();
        ConcurrentPool<ChannelPoolEntry> pool = pools.get(key);
        if (pool == null) {
            log.warn("退役连接时未找到对应的连接池: {}", key);
            return;
        }
        pool.retire(entry);
    }

    /**
     * 检查所有连接池中是否存在 H2 活跃 stream。
     *
     * <p>遍历所有池的所有 {@link ChannelPoolEntry}，通过 Channel pipeline
     * 获取 {@link H2ResponseDemuxHandler} 检查 {@code hasActiveStreams()}。
     *
     * @return 任一连接上有活跃 stream 返回 true
     */
    public boolean hasActiveH2Streams() {
        for (ConcurrentPool<ChannelPoolEntry> pool : pools.values()) {
            for (ChannelPoolEntry entry : pool.getEntries()) {
                Channel ch = entry.getChannel();
                if (!ch.isActive()) {
                    continue;
                }
                H2ResponseDemuxHandler demux = ch.pipeline().get(H2ResponseDemuxHandler.class);
                if (demux != null && demux.hasActiveStreams()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 关闭所有连接池。 */
    public void closeAll() {
        for (ConcurrentPool<ChannelPoolEntry> pool : pools.values()) {
            pool.close();
        }
        pools.clear();
    }

    private PoolConfig toPoolConfig() {
        PoolConfig config = new PoolConfig();
        config.setMaxPoolSize(properties.getMaxConnectionsPerHost());
        config.setMaxIdleTimeSeconds(properties.getMaxIdleTimeSeconds());
        config.setConnectionTimeoutMillis(properties.getConnectTimeoutMillis());
        config.setThreadLocalCacheSize(properties.getThreadLocalCacheSize());
        return config;
    }
}
