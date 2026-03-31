package com.lei.gateway.proxy;

import com.lei.gateway.config.ShutdownProperties;
import io.netty.channel.EventLoopGroup;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 优雅停机协调器，替代 {@link NettyServerBootstrap} 作为生命周期管理者。
 *
 * <p>实现 {@link SmartLifecycle}，编排启动预热和四阶段停机流程。
 * {@code getPhase()} 返回 {@code Integer.MAX_VALUE - 1}，确保最先停机。
 */
public class ShutdownCoordinator implements SmartLifecycle {

    private static final Logger LOG =
            LoggerFactory.getLogger(ShutdownCoordinator.class);

    private final NettyServerBootstrap serverBootstrap;
    private final DrainHandler drainHandler;
    private final InFlightRequestTracker inFlightTracker;
    private final UpstreamConnectionPool connectionPool;
    private final EventLoopGroup workerGroup;
    private final ShutdownProperties shutdownProperties;
    private final WarmupRunner warmupRunner;
    private volatile boolean running;

    /** 创建 ShutdownCoordinator。 */
    public ShutdownCoordinator(NettyServerBootstrap serverBootstrap,
            DrainHandler drainHandler,
            InFlightRequestTracker inFlightTracker,
            UpstreamConnectionPool connectionPool,
            EventLoopGroup workerGroup,
            ShutdownProperties shutdownProperties,
            WarmupRunner warmupRunner) {
        this.serverBootstrap = serverBootstrap;
        this.drainHandler = drainHandler;
        this.inFlightTracker = inFlightTracker;
        this.connectionPool = connectionPool;
        this.workerGroup = workerGroup;
        this.shutdownProperties = shutdownProperties;
        this.warmupRunner = warmupRunner;
    }

    @Override
    public void start() {
        warmupRunner.runWarmup();
        serverBootstrap.start();
        running = true;
    }

    /**
     * 四阶段停机编排。
     *
     * <p>Spring 容器在触发 {@code SmartLifecycle} 停机时调用此方法。
     * {@code callback} 是 Spring 传入的"停机完成通知回调"，必须在停机流程
     * 结束后调用（在 finally 块中），否则 Spring 容器会无限等待。
     *
     * @param callback Spring 容器的停机完成通知回调
     */
    @Override
    public void stop(Runnable callback) {
        long startNanos = System.nanoTime();
        int timeoutSeconds = shutdownProperties.getShutdownTimeoutSeconds();
        int pollIntervalMillis = shutdownProperties.getShutdownPollIntervalMillis();

        LOG.info("优雅停机开始，超时 {} 秒", timeoutSeconds);

        try {
            // 阶段1：关闭 serverChannel + bossGroup
            closeServerChannelAndBossGroup();

            // 阶段2：激活排空
            activateDrain();

            // 阶段3：等待在途请求完成
            awaitInFlightRequests(timeoutSeconds, pollIntervalMillis);

            // 阶段4：关闭连接池 + workerGroup
            closePoolAndWorkerGroup();

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startNanos);
            LOG.info("优雅停机完成，总耗时 {} ms", elapsedMs);
        } finally {
            running = false;
            callback.run();
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    private void closeServerChannelAndBossGroup() {
        try {
            serverBootstrap.closeServerChannelAndBossGroup();
        } catch (Exception e) {
            LOG.error("阶段1：关闭 serverChannel + bossGroup 失败", e);
        }
    }

    private void activateDrain() {
        try {
            drainHandler.activateDrain();
            LOG.info("阶段2：排空已激活");
        } catch (Exception e) {
            LOG.error("阶段2：激活排空失败", e);
        }
    }

    /**
     * 使用 {@link ScheduledExecutorService} 定时轮询在途请求计数和 H2 活跃 stream，
     * 避免 {@code Thread.sleep} 阻塞。通过 {@link CountDownLatch} 等待
     * 在途请求清零且 H2 活跃 stream 全部完成，或超时。
     */
    private void awaitInFlightRequests(int timeoutSeconds,
            int pollIntervalMillis) {
        if (inFlightTracker.getInFlightCount() == 0
                && !connectionPool.hasActiveH2Streams()) {
            LOG.info("阶段3：在途请求和 H2 活跃 stream 已清零");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "shutdown-poll");
                    t.setDaemon(true);
                    return t;
                });

        try {
            ScheduledFuture<?> pollTask = scheduler.scheduleAtFixedRate(() -> {
                int count = inFlightTracker.getInFlightCount();
                boolean h2Active = connectionPool.hasActiveH2Streams();
                if (count <= 0 && !h2Active) {
                    LOG.info("阶段3：在途请求和 H2 活跃 stream 已清零");
                    latch.countDown();
                } else {
                    LOG.info("阶段3：等待完成，在途请求 {}，H2 活跃 stream {}",
                            count, h2Active);
                }
            }, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);

            boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            pollTask.cancel(false);

            if (!completed) {
                LOG.warn("阶段3：等待超时，剩余在途请求 {}，H2 活跃 stream {}",
                        inFlightTracker.getInFlightCount(),
                        connectionPool.hasActiveH2Streams());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("阶段3：等待在途请求被中断", e);
        } catch (Exception e) {
            LOG.error("阶段3：等待在途请求异常", e);
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * 先关闭连接池，再关闭 workerGroup。
     *
     * <p>顺序说明：连接池中的 upstream channel 注册在 workerGroup 的 EventLoop 上，
     * {@code closeAll()} 会触发这些 channel 的 close 事件，需要 EventLoop 处理。
     * 如果先关 workerGroup，channel close 事件无法被处理，可能导致资源泄漏。
     * 因此必须先关连接池，等 channel 全部关闭后再关 workerGroup。
     */
    private void closePoolAndWorkerGroup() {
        try {
            connectionPool.closeAll();
        } catch (Exception e) {
            LOG.error("阶段4：关闭连接池失败", e);
        }
        try {
            workerGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("阶段4：等待 workerGroup 关闭被中断", e);
        } catch (Exception e) {
            LOG.error("阶段4：关闭 workerGroup 失败", e);
        }
    }
}
