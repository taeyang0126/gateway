package com.example.gateway.pool;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * 用于测试的 {@link PoolEntry} 简单实现。
 */
class TestPoolEntry implements PoolEntry {

    private static final AtomicIntegerFieldUpdater<TestPoolEntry> STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(TestPoolEntry.class, "state");

    volatile int state = STATE_NOT_IN_USE;
    private volatile long lastAccessTime = System.nanoTime();
    private volatile boolean alive = true;
    private volatile boolean closed = false;

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
        closed = true;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }
}
