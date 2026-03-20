package com.example.gateway.core.proxy;

import com.example.gateway.pool.PoolEntry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * 将 Netty {@link Channel} 适配为 {@link PoolEntry}。
 *
 * <p>使用 {@link AtomicIntegerFieldUpdater} 实现无锁 CAS 状态切换。
 */
public class ChannelPoolEntry implements PoolEntry {

    private static final AtomicIntegerFieldUpdater<ChannelPoolEntry> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelPoolEntry.class, "state");

    /** Channel Attribute key：存储关联的 ChannelPoolEntry。 */
    public static final AttributeKey<ChannelPoolEntry> POOL_ENTRY_KEY =
            AttributeKey.valueOf("poolEntry");

    private final Channel channel;
    private final String poolKey;
    private volatile int state = STATE_NOT_IN_USE;
    private volatile long lastAccessTime;

    /** 创建 ChannelPoolEntry，并将自身存入 Channel Attribute。 */
    public ChannelPoolEntry(Channel channel, String poolKey) {
        this.channel = channel;
        this.poolKey = poolKey;
        this.lastAccessTime = System.nanoTime();
        channel.attr(POOL_ENTRY_KEY).set(this);
    }

    /** 返回此条目所属的连接池 key（host:port）。 */
    public String getPoolKey() {
        return poolKey;
    }

    /** 返回底层 Netty Channel。 */
    public Channel getChannel() {
        return channel;
    }

    @Override
    public int getState() {
        return state;
    }

    @Override
    public boolean compareAndSet(int expect, int update) {
        return STATE_UPDATER.compareAndSet(this, expect, update);
    }

    @Override
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    @Override
    public void setLastAccessTime(long time) {
        this.lastAccessTime = time;
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public boolean isAlive() {
        return channel.isActive();
    }
}
