package com.lei.gateway.pool;

/**
 * 池化条目接口，基于 CAS 的状态管理。
 *
 * <p>参考 HikariCP 的 IConcurrentBagEntry 设计。
 */
public interface PoolEntry {

    int STATE_NOT_IN_USE = 0;
    int STATE_IN_USE = 1;
    int STATE_REMOVED = 2;

    /** 返回当前条目状态。 */
    int getState();

    /** 原子地将状态从 {@code expect} 切换为 {@code update}。 */
    boolean compareAndSet(int expect, int update);

    /** 返回最后访问时间（纳秒，来自 {@link System#nanoTime()}）。 */
    long getLastAccessTime();

    /** 设置最后访问时间（纳秒）。 */
    void setLastAccessTime(long time);

    /** 关闭底层资源。 */
    void close();

    /** 底层资源是否仍然可用。 */
    boolean isAlive();
}
