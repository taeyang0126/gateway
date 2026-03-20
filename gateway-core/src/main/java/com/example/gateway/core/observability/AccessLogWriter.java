package com.example.gateway.core.observability;

import com.example.gateway.core.config.ObservabilityProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结构化访问日志输出器，使用 SLF4J 输出 JSON 格式访问日志。
 *
 * <p>日志级别由 {@link ObservabilityProperties#getAccessLogLevel()} 控制，
 * {@code accessLogEnabled=false} 时为空操作。
 */
@Component
public class AccessLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogWriter.class);

    private final ObservabilityProperties config;
    private final ObjectMapper objectMapper;

    /** 创建访问日志输出器。 */
    public AccessLogWriter(ObservabilityProperties config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 输出一条 JSON 格式的访问日志。
     *
     * @param entry 访问日志条目
     */
    public void log(AccessLogEntry entry) {
        if (!config.isAccessLogEnabled()) {
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException ex) {
            log.error("访问日志序列化失败", ex);
            return;
        }

        logAtConfiguredLevel(json);
    }

    private void logAtConfiguredLevel(String message) {
        switch (config.getAccessLogLevel().toUpperCase()) {
            case "TRACE" -> log.trace(message);
            case "DEBUG" -> log.debug(message);
            case "INFO" -> log.info(message);
            case "ERROR" -> log.error(message);
            default -> log.warn(message);
        }
    }
}
