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

import java.util.List;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AsciiString;

/** 网关 HTTP 请求头常量。 */
public final class HttpHeaderConstants {

    public static final CharSequence TRACE_ID_HEADER = "X-Trace-Id";
    public static final CharSequence X_FORWARDED_FOR = "X-Forwarded-For";
    public static final CharSequence X_FORWARDED_PROTO = "X-Forwarded-Proto";
    public static final CharSequence X_FORWARDED_HOST = "X-Forwarded-Host";
    public static final String FORWARDED_PROTO_HTTP = "http";
    public static final CharSequence KEEP_ALIVE_HEADER = AsciiString.cached("Keep-Alive");
    public static final CharSequence PROXY_AUTHENTICATE = "Proxy-Authenticate";
    public static final CharSequence PROXY_AUTHORIZATION = "Proxy-Authorization";

    /** Hop-by-hop 请求头（仅对当前一跳连接生效），网关转发到上游前必须移除， 避免把连接级语义错误透传到下一跳。 */
    public static final List<CharSequence> HOP_BY_HOP_HEADERS =
            List.of(
                    HttpHeaderNames.CONNECTION,
                    KEEP_ALIVE_HEADER,
                    HttpHeaderNames.TE,
                    HttpHeaderNames.TRAILER,
                    HttpHeaderNames.UPGRADE,
                    PROXY_AUTHENTICATE,
                    PROXY_AUTHORIZATION,
                    HttpHeaderNames.TRANSFER_ENCODING);

    private HttpHeaderConstants() {
        // utility class
    }
}
