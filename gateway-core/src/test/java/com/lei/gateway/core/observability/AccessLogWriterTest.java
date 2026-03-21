package com.lei.gateway.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lei.gateway.core.config.ObservabilityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * AccessLogWriter 单元测试。
 */
class AccessLogWriterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(AccessLogWriter.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        logger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
    }

    private AccessLogEntry createEntry() {
        AccessLogEntry entry = new AccessLogEntry();
        entry.setMethod("POST");
        entry.setPath("/api/example/echo");
        entry.setStatusCode(200);
        entry.setDurationMs(15);
        entry.setClientIp("10.0.0.1");
        entry.setUpstream("http://localhost:8081");
        entry.setRequestBodySize(512);
        entry.setResponseBodySize(512);
        entry.setTraceId("abcdef1234567890abcdef1234567890");
        return entry;
    }

    @Test
    void logsJsonWithAllFields() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setAccessLogEnabled(true);
        config.setAccessLogLevel("WARN");
        AccessLogWriter writer = new AccessLogWriter(config, OBJECT_MAPPER);

        writer.log(createEntry());

        assertThat(listAppender.list).hasSize(1);
        ILoggingEvent event = listAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        String message = event.getFormattedMessage();
        assertThat(message).contains("\"method\":\"POST\"");
        assertThat(message).contains("\"path\":\"/api/example/echo\"");
        assertThat(message).contains("\"statusCode\":200");
        assertThat(message).contains("\"durationMs\":15");
        assertThat(message).contains("\"clientIp\":\"10.0.0.1\"");
        assertThat(message).contains("\"upstream\":\"http://localhost:8081\"");
        assertThat(message).contains("\"requestBodySize\":512");
        assertThat(message).contains("\"responseBodySize\":512");
        assertThat(message).contains("\"traceId\":\"abcdef1234567890abcdef1234567890\"");
    }

    @Test
    void disabledDoesNotLog() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setAccessLogEnabled(false);
        AccessLogWriter writer = new AccessLogWriter(config, OBJECT_MAPPER);

        writer.log(createEntry());

        assertThat(listAppender.list).isEmpty();
    }

    @Test
    void respectsLogLevel_info() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setAccessLogEnabled(true);
        config.setAccessLogLevel("INFO");
        AccessLogWriter writer = new AccessLogWriter(config, OBJECT_MAPPER);

        writer.log(createEntry());

        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void respectsLogLevel_debug() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setAccessLogEnabled(true);
        config.setAccessLogLevel("DEBUG");
        AccessLogWriter writer = new AccessLogWriter(config, OBJECT_MAPPER);

        writer.log(createEntry());

        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }

    @Test
    void respectsLogLevel_error() {
        ObservabilityProperties config = new ObservabilityProperties();
        config.setAccessLogEnabled(true);
        config.setAccessLogLevel("ERROR");
        AccessLogWriter writer = new AccessLogWriter(config, OBJECT_MAPPER);

        writer.log(createEntry());

        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    }
}
