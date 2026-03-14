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
package com.lei.java.gateway.server.http;

import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;

/** 阶段 1 默认错误映射器。 */
public final class DefaultErrorResponseMapper implements ErrorResponseMapper {

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_BAD_GATEWAY = 502;
    private static final int STATUS_GATEWAY_TIMEOUT = 504;
    private static final int STATUS_INTERNAL_SERVER_ERROR = 500;

    @Override
    public ErrorResponse map(final Throwable throwable, final String traceId) {
        Objects.requireNonNull(throwable, "throwable must not be null");
        final String normalizedTraceId = Objects.requireNonNullElse(traceId, "");

        if (containsCause(throwable, DefaultErrorResponseMapper::isBadRequestCause)) {
            return build(
                    normalizedTraceId,
                    "GATEWAY_ERROR",
                    "BAD_REQUEST",
                    "bad request",
                    STATUS_BAD_REQUEST);
        }
        if (containsCause(throwable, DefaultErrorResponseMapper::isTimeoutCause)) {
            return build(
                    normalizedTraceId,
                    "UPSTREAM_ERROR",
                    "UPSTREAM_TIMEOUT",
                    "upstream timeout",
                    STATUS_GATEWAY_TIMEOUT);
        }
        if (containsCause(throwable, DefaultErrorResponseMapper::isUpstreamUnavailableCause)) {
            return build(
                    normalizedTraceId,
                    "UPSTREAM_ERROR",
                    "UPSTREAM_UNAVAILABLE",
                    "upstream unavailable",
                    STATUS_BAD_GATEWAY);
        }
        return build(
                normalizedTraceId,
                "GATEWAY_ERROR",
                "GATEWAY_INTERNAL_ERROR",
                "gateway internal error",
                STATUS_INTERNAL_SERVER_ERROR);
    }

    private static ErrorResponse build(
            final String traceId,
            final String category,
            final String code,
            final String message,
            final int status) {
        return new ErrorResponse(
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                traceId,
                category,
                code,
                message,
                status);
    }

    private static boolean containsCause(
            final Throwable throwable, final java.util.function.Predicate<Throwable> predicate) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            if (predicate.test(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static boolean isBadRequestCause(final Throwable throwable) {
        return throwable instanceof IllegalArgumentException
                || throwable instanceof DecoderException;
    }

    private static boolean isTimeoutCause(final Throwable throwable) {
        return throwable instanceof ReadTimeoutException
                || throwable instanceof WriteTimeoutException
                || throwable instanceof ConnectTimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof TimeoutException;
    }

    private static boolean isUpstreamUnavailableCause(final Throwable throwable) {
        return throwable instanceof ConnectException
                || throwable instanceof BindException
                || throwable instanceof NoRouteToHostException
                || throwable instanceof java.nio.channels.UnresolvedAddressException
                || throwable instanceof java.nio.channels.ClosedChannelException;
    }
}
