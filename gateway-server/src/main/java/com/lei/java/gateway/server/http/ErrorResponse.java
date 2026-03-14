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

/**
 * 统一错误响应体。
 *
 * @param timestamp 响应时间戳
 * @param traceId 链路追踪标识
 * @param category 错误类别
 * @param code 错误码
 * @param message 错误描述
 * @param status HTTP 状态码
 */
public record ErrorResponse(
        String timestamp,
        String traceId,
        String category,
        String code,
        String message,
        int status) {

    /**
     * 将错误响应编码为 JSON。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        return "{"
                + "\"timestamp\":\""
                + escapeJson(timestamp)
                + "\","
                + "\"traceId\":\""
                + escapeJson(traceId)
                + "\","
                + "\"category\":\""
                + escapeJson(category)
                + "\","
                + "\"code\":\""
                + escapeJson(code)
                + "\","
                + "\"message\":\""
                + escapeJson(message)
                + "\","
                + "\"status\":"
                + status
                + "}";
    }

    private static String escapeJson(final String value) {
        if (value == null) {
            return "";
        }
        final StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            final char ch = value.charAt(index);
            if (ch == '\\' || ch == '"') {
                builder.append('\\').append(ch);
            } else if (ch == '\n') {
                builder.append("\\n");
            } else if (ch == '\r') {
                builder.append("\\r");
            } else if (ch == '\t') {
                builder.append("\\t");
            } else if (ch < 0x20) {
                builder.append('?');
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
