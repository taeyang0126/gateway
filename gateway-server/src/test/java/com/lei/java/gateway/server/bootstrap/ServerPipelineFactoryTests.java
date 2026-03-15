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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.config.GatewayServerConfig;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;

class ServerPipelineFactoryTests {

    @Test
    void shouldRegisterKeepAliveRelatedHandlersWhenConfigurePipeline() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ServerPipelineFactory factory =
                    new ServerPipelineFactory(
                            new GatewayServerConfig(8080, 1024, true, java.util.List.of()));

            factory.configure(channel.pipeline());

            assertNotNull(channel.pipeline().get(HttpServerCodec.class));
            assertNotNull(channel.pipeline().get(HttpServerKeepAliveHandler.class));
            assertNotNull(channel.pipeline().get(DefaultHttpServerHandler.class));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
