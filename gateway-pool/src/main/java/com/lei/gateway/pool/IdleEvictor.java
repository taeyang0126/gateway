package com.lei.gateway.pool;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空闲条目清理器，定时扫描池中超过 {@link PoolConfig#getMaxIdleTimeSeconds()} 的空闲条目并移除。
 */
class IdleEvictor<T extends PoolEntry> implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(IdleEvictor.class);

    private final ConcurrentPool<T> pool;

    IdleEvictor(ConcurrentPool<T> pool) {
        this.pool = pool;
    }

    @Override
    public void run() {
        long maxIdleNanos =
                TimeUnit.SECONDS.toNanos(pool.getConfig().getMaxIdleTimeSeconds());
        long now = System.nanoTime();

        for (T entry : pool.getSharedList()) {
            if (entry.getState() == PoolEntry.STATE_NOT_IN_USE
                    && (now - entry.getLastAccessTime()) > maxIdleNanos) {
                pool.remove(entry);
                log.debug("清理空闲池化条目: {}", entry);
            }
        }
    }
}
