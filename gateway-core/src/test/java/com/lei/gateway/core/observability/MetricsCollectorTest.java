package com.lei.gateway.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.ObservabilityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * MetricsCollector 单元测试。
 */
class MetricsCollectorTest {

    private ObservabilityProperties enabledConfig() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setMetricsEnabled(true);
        return config;
    }

    private ObservabilityProperties disabledConfig() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setMetricsEnabled(false);
        return config;
    }

    @Test
    void recordRequest_incrementsCounterAndTimer() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordRequest("GET", "/api/test", 200,
                TimeUnit.MILLISECONDS.toNanos(50));

        Counter total = registry.find("gateway.requests.total")
                .tag("method", "GET")
                .tag("path", "/api/test")
                .tag("status", "200")
                .counter();
        assertThat(total).isNotNull();
        assertThat(total.count()).isEqualTo(1.0);

        Timer duration = registry.find("gateway.requests.duration")
                .tag("method", "GET")
                .tag("path", "/api/test")
                .timer();
        assertThat(duration).isNotNull();
        assertThat(duration.count()).isEqualTo(1);
    }

    @Test
    void recordRequest_4xxIncrementsErrorCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordRequest("GET", "/api/test", 404,
                TimeUnit.MILLISECONDS.toNanos(10));

        Counter errors = registry.find("gateway.requests.errors")
                .tag("status_class", "4xx")
                .counter();
        assertThat(errors).isNotNull();
        assertThat(errors.count()).isEqualTo(1.0);
    }

    @Test
    void recordRequest_5xxIncrementsErrorCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordRequest("POST", "/api/test", 502,
                TimeUnit.MILLISECONDS.toNanos(100));

        Counter errors = registry.find("gateway.requests.errors")
                .tag("status_class", "5xx")
                .counter();
        assertThat(errors).isNotNull();
        assertThat(errors.count()).isEqualTo(1.0);
    }

    @Test
    void recordRequest_2xxDoesNotIncrementErrorCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordRequest("GET", "/api/test", 200,
                TimeUnit.MILLISECONDS.toNanos(10));

        Counter errors = registry.find("gateway.requests.errors").counter();
        assertThat(errors).isNull();
    }

    @Test
    void recordRequest_disabledIsNoop() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, disabledConfig());

        collector.recordRequest("GET", "/api/test", 200,
                TimeUnit.MILLISECONDS.toNanos(10));

        assertThat(registry.find("gateway.requests.total").counter()).isNull();
        assertThat(registry.find("gateway.requests.duration").timer()).isNull();
    }

    @Test
    void recordUpstreamConnect_recordsTimerAndSlowCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        // 100ms 连接，阈值 50ms → 慢连接
        collector.recordUpstreamConnect("localhost:8081",
                TimeUnit.MILLISECONDS.toNanos(100), true, 50);

        Timer connectTimer = registry.find("gateway.upstream.connect.duration")
                .tag("upstream", "localhost:8081")
                .timer();
        assertThat(connectTimer).isNotNull();
        assertThat(connectTimer.count()).isEqualTo(1);

        Counter slowCounter = registry.find("gateway.upstream.connect.slow")
                .tag("upstream", "localhost:8081")
                .counter();
        assertThat(slowCounter).isNotNull();
        assertThat(slowCounter.count()).isEqualTo(1.0);
    }

    @Test
    void recordUpstreamConnect_fastConnectionNoSlowCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        // 10ms 连接，阈值 50ms → 不是慢连接，不记录 timer 和 slow counter
        collector.recordUpstreamConnect("localhost:8081",
                TimeUnit.MILLISECONDS.toNanos(10), true, 50);

        assertThat(registry.find("gateway.upstream.connect.slow").counter()).isNull();
        assertThat(registry.find("gateway.upstream.connect.duration").timer()).isNull();
    }

    @Test
    void recordUpstreamConnect_failureIncrementsFailureCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordUpstreamConnect("localhost:8081",
                TimeUnit.MILLISECONDS.toNanos(500), false, 50);

        Counter failures = registry.find("gateway.upstream.connect.failures")
                .tag("upstream", "localhost:8081")
                .counter();
        assertThat(failures).isNotNull();
        assertThat(failures.count()).isEqualTo(1.0);
    }

    @Test
    void recordPoolBorrowFailure_incrementsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordPoolBorrowFailure("localhost:8081");

        Counter borrowFailures = registry.find("gateway.pool.borrow.failures")
                .tag("upstream", "localhost:8081")
                .counter();
        assertThat(borrowFailures).isNotNull();
        assertThat(borrowFailures.count()).isEqualTo(1.0);
    }

    @Test
    void recordPoolBorrowFailure_disabledIsNoop() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, disabledConfig());

        collector.recordPoolBorrowFailure("localhost:8081");

        assertThat(registry.find("gateway.pool.borrow.failures").counter()).isNull();
    }

    @Test
    void recordSecurityFilterDecision_recordsCounterWithTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordSecurityFilterDecision("auth", "DENY",
                "missing_authorization_header", "r1");

        Counter counter = registry.find("gateway.security.filter.decisions")
                .tag("filter", "auth")
                .tag("decision", "DENY")
                .tag("reason", "missing_authorization_header")
                .tag("routeId", "r1")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordSecurityFilterDuration_recordsTimerWithTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordSecurityFilterDuration("ip-access", "r2",
                TimeUnit.MILLISECONDS.toNanos(12));

        Timer timer = registry.find("gateway.security.filter.duration")
                .tag("filter", "ip-access")
                .tag("routeId", "r2")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordSecurityAuxiliaryMetrics_recordsTaggedCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsCollector collector = new MetricsCollector(registry, enabledConfig());

        collector.recordAuthFailure("token_expired");
        collector.recordRateLimitHit("ip-rate-limit", "r3");
        collector.recordSecurityFallback("rate-limit", "distributed_to_local");

        Counter authFailure = registry.find("gateway.security.auth.failures")
                .tag("reason", "token_expired")
                .counter();
        Counter rateLimitHit = registry.find("gateway.security.rate_limit.hits")
                .tag("stage", "ip-rate-limit")
                .tag("routeId", "r3")
                .counter();
        Counter fallback = registry.find("gateway.security.fallbacks")
                .tag("component", "rate-limit")
                .tag("reason", "distributed_to_local")
                .counter();

        assertThat(authFailure).isNotNull();
        assertThat(authFailure.count()).isEqualTo(1.0);
        assertThat(rateLimitHit).isNotNull();
        assertThat(rateLimitHit.count()).isEqualTo(1.0);
        assertThat(fallback).isNotNull();
        assertThat(fallback.count()).isEqualTo(1.0);
    }
}
