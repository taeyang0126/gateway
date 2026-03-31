package com.lei.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class InFlightRequestTrackerTest {

    private InFlightRequestTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new InFlightRequestTracker();
    }

    @Test
    void initialCountIsZero() {
        assertThat(tracker.getInFlightCount()).isZero();
    }

    @Test
    void incrementIncreasesCount() {
        tracker.increment();
        assertThat(tracker.getInFlightCount()).isEqualTo(1);

        tracker.increment();
        assertThat(tracker.getInFlightCount()).isEqualTo(2);
    }

    @Test
    void decrementDecreasesCount() {
        tracker.increment();
        tracker.increment();
        tracker.decrement();
        assertThat(tracker.getInFlightCount()).isEqualTo(1);
    }

    @Test
    void incrementThenDecrementReturnsToZero() {
        tracker.increment();
        tracker.decrement();
        assertThat(tracker.getInFlightCount()).isZero();
    }

    @Test
    void decrementAtZeroDoesNotGoNegative() {
        tracker.decrement();
        assertThat(tracker.getInFlightCount()).isZero();
    }

    @Test
    void decrementAtZeroLogsWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(InFlightRequestTracker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            tracker.decrement();

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("WARN");
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("double-decrement");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void multipleDecrementsAtZeroNeverGoNegative() {
        tracker.decrement();
        tracker.decrement();
        tracker.decrement();
        assertThat(tracker.getInFlightCount()).isZero();
    }
}
