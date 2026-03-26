package com.lei.gateway.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lei.gateway.core.config.ShutdownProperties;
import io.netty.channel.EventLoopGroup;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ShutdownCoordinatorTest {

    private NettyServerBootstrap serverBootstrap;
    private DrainHandler drainHandler;
    private InFlightRequestTracker inFlightTracker;
    private UpstreamConnectionPool connectionPool;
    private EventLoopGroup workerGroup;
    private ShutdownProperties shutdownProperties;
    private WarmupRunner warmupRunner;
    private ShutdownCoordinator coordinator;

    @BeforeEach
    void setUp() {
        serverBootstrap = mock(NettyServerBootstrap.class);
        drainHandler = mock(DrainHandler.class);
        inFlightTracker = mock(InFlightRequestTracker.class);
        connectionPool = mock(UpstreamConnectionPool.class);
        workerGroup = mock(EventLoopGroup.class);
        shutdownProperties = new ShutdownProperties();
        shutdownProperties.setShutdownTimeoutSeconds(2);
        shutdownProperties.setShutdownPollIntervalMillis(50);
        warmupRunner = mock(WarmupRunner.class);

        @SuppressWarnings("unchecked")
        io.netty.util.concurrent.Future<Object> succeededFuture =
                mock(io.netty.util.concurrent.Future.class);
        when(workerGroup.shutdownGracefully())
                .thenAnswer(inv -> succeededFuture);
        try {
            when(succeededFuture.sync()).thenReturn(succeededFuture);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        coordinator = new ShutdownCoordinator(serverBootstrap, drainHandler,
                inFlightTracker, connectionPool, workerGroup,
                shutdownProperties, warmupRunner);
    }

    @Test
    void startExecutesWarmupThenBootstrap() {
        coordinator.start();

        InOrder order = inOrder(warmupRunner, serverBootstrap);
        order.verify(warmupRunner).runWarmup();
        order.verify(serverBootstrap).start();
        assertThat(coordinator.isRunning()).isTrue();
    }

    @Test
    void phaseIsMaxValueMinusOne() {
        assertThat(coordinator.getPhase()).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void stopExecutesFourPhasesInOrder() {
        when(inFlightTracker.getInFlightCount()).thenReturn(0);
        coordinator.start();

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        coordinator.stop(() -> callbackCalled.set(true));

        InOrder order = inOrder(serverBootstrap, drainHandler,
                inFlightTracker, connectionPool, workerGroup);
        order.verify(serverBootstrap).closeServerChannelAndBossGroup();
        order.verify(drainHandler).activateDrain();
        order.verify(inFlightTracker).getInFlightCount();
        order.verify(connectionPool).closeAll();
        order.verify(workerGroup).shutdownGracefully();

        assertThat(callbackCalled).isTrue();
        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    void stopWaitsForInFlightRequestsThenCompletes() {
        AtomicInteger callCount = new AtomicInteger(0);
        when(inFlightTracker.getInFlightCount()).thenAnswer(inv -> {
            // 前两次返回 > 0，第三次返回 0
            return callCount.incrementAndGet() <= 2 ? 1 : 0;
        });
        coordinator.start();

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        coordinator.stop(() -> callbackCalled.set(true));

        assertThat(callbackCalled).isTrue();
        verify(connectionPool).closeAll();
        verify(workerGroup).shutdownGracefully();
    }

    @Test
    void stopTimesOutWhenInFlightRequestsNeverReachZero() {
        shutdownProperties.setShutdownTimeoutSeconds(1);
        shutdownProperties.setShutdownPollIntervalMillis(100);
        when(inFlightTracker.getInFlightCount()).thenReturn(5);
        coordinator.start();

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        coordinator.stop(() -> callbackCalled.set(true));

        // 即使超时，callback 仍然被调用，连接池和 workerGroup 仍然被关闭
        assertThat(callbackCalled).isTrue();
        verify(connectionPool).closeAll();
        verify(workerGroup).shutdownGracefully();
    }

    @Test
    void stopCallsCallbackEvenWhenPhase1Throws() {
        doAnswer(inv -> {
            throw new RuntimeException("serverChannel close failed");
        }).when(serverBootstrap).closeServerChannelAndBossGroup();
        when(inFlightTracker.getInFlightCount()).thenReturn(0);
        coordinator.start();

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        coordinator.stop(() -> callbackCalled.set(true));

        assertThat(callbackCalled).isTrue();
        // 后续阶段仍然执行
        verify(drainHandler).activateDrain();
        verify(connectionPool).closeAll();
    }

    @Test
    void stopCallsCallbackEvenWhenPoolCloseThrows() {
        when(inFlightTracker.getInFlightCount()).thenReturn(0);
        doAnswer(inv -> {
            throw new RuntimeException("pool close failed");
        }).when(connectionPool).closeAll();
        coordinator.start();

        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        coordinator.stop(() -> callbackCalled.set(true));

        assertThat(callbackCalled).isTrue();
        verify(workerGroup).shutdownGracefully();
    }

    @Test
    void stopWithZeroInFlightCompletesImmediately() {
        when(inFlightTracker.getInFlightCount()).thenReturn(0);
        coordinator.start();

        long startMs = System.currentTimeMillis();
        AtomicBoolean callbackCalled = new AtomicBoolean(false);
        coordinator.stop(() -> callbackCalled.set(true));
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(callbackCalled).isTrue();
        // 在途请求为 0 时应立即完成，不等待 poll interval
        assertThat(elapsedMs).isLessThan(1000);
    }
}
