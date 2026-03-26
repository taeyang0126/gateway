package com.lei.gateway.core.proxy;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 线程安全的在途请求计数器。
 *
 * <p>基于 {@link AtomicInteger} 实现，供 Netty EventLoop 多线程并发访问。
 * 在 {@code RoutingHandler} 分发请求时 {@link #increment()}，
 * 在 {@code ProxyHandler} 完成或异常终止时 {@link #decrement()}。
 */
public class InFlightRequestTracker {

    private static final Logger LOG =
            LoggerFactory.getLogger(InFlightRequestTracker.class);

    private final AtomicInteger count = new AtomicInteger(0);

    /** 在途请求 +1。 */
    public void increment() {
        count.incrementAndGet();
    }

    /**
     * 在途请求 -1。
     *
     * <p>防御性检查：若当前值 &lt;= 0 则记录 WARN 日志，不减为负数。
     */
    public void decrement() {
        int prev;
        do {
            prev = count.get();
            if (prev <= 0) {
                LOG.warn("decrement() called when in-flight count is already {}; "
                        + "possible double-decrement bug", prev);
                return;
            }
        } while (!count.compareAndSet(prev, prev - 1));
    }

    /** 查询当前在途请求数。 */
    public int getInFlightCount() {
        return count.get();
    }
}
