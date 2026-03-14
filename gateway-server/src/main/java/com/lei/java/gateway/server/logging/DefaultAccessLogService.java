/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 基于 SLF4J 的访问日志实现。 */
public final class DefaultAccessLogService implements AccessLogService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultAccessLogService.class);

    @Override
    public void logSuccess(
            final String traceId,
            final String uri,
            final int status,
            final long latencyMs,
            final String upstream) {
        LOGGER.info(format(traceId, uri, status, latencyMs, upstream, "-"));
    }

    @Override
    public void logFailure(
            final String traceId,
            final String uri,
            final int status,
            final String errorCode,
            final long latencyMs,
            final String upstream) {
        LOGGER.warn(format(traceId, uri, status, latencyMs, upstream, normalize(errorCode)));
    }

    private static String format(
            final String traceId,
            final String uri,
            final int status,
            final long latencyMs,
            final String upstream,
            final String errorCode) {
        return "traceId="
                + normalize(traceId)
                + " uri="
                + normalize(uri)
                + " status="
                + status
                + " latencyMs="
                + latencyMs
                + " upstream="
                + normalize(upstream)
                + " errorCode="
                + normalize(errorCode);
    }

    private static String normalize(final String value) {
        if (value == null) {
            return "-";
        }
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "-";
        }
        return trimmed.replace(' ', '_');
    }
}
