package com.example.gateway.core.proxy;

import com.example.gateway.core.config.ConnectionPoolProperties;
import com.example.gateway.core.observability.MetricsCollector;
import com.example.gateway.pool.ConcurrentPool;
import com.example.gateway.pool.PoolConfig;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.concurrent.CompletableFuture;
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
                    properties.getConnectTimeoutMillis());
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
                    throw e instanceof java.util.concurrent.CompletionException
                            ? (java.util.concurrent.CompletionException) e
                            : new java.util.concurrent.CompletionException(e);
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
        return config;
    }
}
