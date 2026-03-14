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
package com.lei.java.gateway.server.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;

class HealthEndpointTests {

    @Test
    void shouldReturnOkWhenRequestHealth() throws Exception {
        final GatewayBootstrap bootstrap = new GatewayBootstrap();
        bootstrap.start(new GatewayServerConfig(0, 1024 * 1024, true, List.of()));

        try {
            final HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + bootstrap.boundPort()
                                                    + "/health"))
                            .GET()
                            .build();

            final HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, response.statusCode());
            assertEquals("OK", response.body());
        } finally {
            bootstrap.stop();
        }
    }
}
