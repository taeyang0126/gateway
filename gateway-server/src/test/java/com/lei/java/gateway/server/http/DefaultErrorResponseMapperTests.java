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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ConnectException;

import org.junit.jupiter.api.Test;

import io.netty.handler.timeout.ReadTimeoutException;

class DefaultErrorResponseMapperTests {

    @Test
    void shouldMapConnectionErrorToUpstreamUnavailable() {
        final ErrorResponseMapper mapper = new DefaultErrorResponseMapper();

        final ErrorResponse response = mapper.map(new ConnectException("refused"), "trace-1");

        assertEquals(502, response.status());
        assertEquals("UPSTREAM_ERROR", response.category());
        assertEquals("UPSTREAM_UNAVAILABLE", response.code());
        assertEquals("trace-1", response.traceId());
    }

    @Test
    void shouldMapReadTimeoutToGatewayTimeout() {
        final ErrorResponseMapper mapper = new DefaultErrorResponseMapper();

        final ErrorResponse response = mapper.map(ReadTimeoutException.INSTANCE, "trace-2");

        assertEquals(504, response.status());
        assertEquals("UPSTREAM_ERROR", response.category());
        assertEquals("UPSTREAM_TIMEOUT", response.code());
        assertEquals("trace-2", response.traceId());
    }

    @Test
    void shouldMapIllegalArgumentToBadRequest() {
        final ErrorResponseMapper mapper = new DefaultErrorResponseMapper();

        final ErrorResponse response = mapper.map(new IllegalArgumentException("bad"), "trace-3");

        assertEquals(400, response.status());
        assertEquals("GATEWAY_ERROR", response.category());
        assertEquals("BAD_REQUEST", response.code());
    }
}
