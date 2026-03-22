package com.lei.gateway.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * PoolEntryFactory 默认方法测试。
 */
class PoolEntryFactoryTest {

    @Test
    void createAsync_defaultImpl_delegatesToCreate() throws Exception {
        PoolEntryFactory<TestPoolEntry> factory = TestPoolEntry::new;

        CompletableFuture<TestPoolEntry> future = factory.createAsync();
        TestPoolEntry entry = future.get(500, TimeUnit.MILLISECONDS);

        assertThat(entry).isNotNull();
        assertThat(entry.getState()).isEqualTo(PoolEntry.STATE_NOT_IN_USE);
    }

    @Test
    void createAsync_defaultImpl_wrapsExceptionAsCompletionException() {
        PoolEntryFactory<TestPoolEntry> failFactory = () -> {
            throw new RuntimeException("create failed");
        };

        CompletableFuture<TestPoolEntry> future = failFactory.createAsync();

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(future.isCompletedExceptionally()).isTrue();
    }
}
