package com.lei.gateway.pool;

import java.util.concurrent.CompletableFuture;

/**
 * 异步创建新池化条目的工厂接口。
 *
 * @param <T> 池化条目类型
 */
public interface PoolEntryFactory<T extends PoolEntry> {

    /**
     * 异步创建一个新的池化条目。
     *
     * @return 包含新条目的 CompletableFuture，条目初始状态为 {@link PoolEntry#STATE_NOT_IN_USE}
     */
    CompletableFuture<T> createAsync();
}
