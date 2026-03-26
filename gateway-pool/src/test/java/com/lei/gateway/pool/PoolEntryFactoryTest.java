package com.lei.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * PoolEntryFactory 接口测试。
 */
class PoolEntryFactoryTest {

    @Test
    void createAsync_returnsCompletedFuture() throws Exception {
        PoolEntryFactory<TestPoolEntry> factory =
                () -> CompletableFuture.completedFuture(new TestPoolEntry());

        CompletableFuture<TestPoolEntry> future = factory.createAsync();
        TestPoolEntry entry = future.get(500, TimeUnit.MILLISECONDS);

        assertThat(entry).isNotNull();
        assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_NOT_IN_USE);
    }

    @Test
    void createAsync_failedFuture_propagatesException() {
        PoolEntryFactory<TestPoolEntry> failFactory =
                () -> CompletableFuture.failedFuture(new RuntimeException("create failed"));

        CompletableFuture<TestPoolEntry> future = failFactory.createAsync();

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(future.isCompletedExceptionally()).isTrue();
    }
}
