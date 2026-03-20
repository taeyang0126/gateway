package com.example.gateway.pool;

import java.util.concurrent.CompletableFuture;

/**
 * 创建新池化条目的工厂接口。
 *
 * @param <T> 池化条目类型
 */
public interface PoolEntryFactory<T extends PoolEntry> {

    /**
     * 同步创建一个新的池化条目。
     *
     * @return 初始状态为 {@link PoolEntry#STATE_NOT_IN_USE} 的新条目
     * @throws Exception 创建失败时抛出
     */
    T create() throws Exception;

    /**
     * 异步创建一个新的池化条目。
     *
     * <p>默认实现委托给 {@link #create()}，子类可覆盖以提供真正的异步实现。
     *
     * @return 包含新条目的 CompletableFuture
     */
    default CompletableFuture<T> createAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return create();
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }
}
