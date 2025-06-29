/*
 * Copyright (c) 2025 The gateway Project
 * https://github.com/taeyang0126/gateway
 *
 * Licensed under the MIT License.
 * You may obtain a copy of the License at
 *
 *     https://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server.trace;

import io.netty.util.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

/**
 * <p>
 * TracingAttributes
 * </p>
 *
 * @author 伍磊
 */
public final class TracingAttributes {
    // 定义一个静态的、唯一的 AttributeKey 用于在 Channel 中存储和检索 Span
    public static final AttributeKey<Span> SERVER_SPAN_KEY =
            AttributeKey.valueOf("otel.server.span");

    // 用于在 Channel 中暂存请求开始时间的 Key (使用纳秒更精确)
    public static final AttributeKey<Long> START_TIME_KEY =
            AttributeKey.valueOf("otel.start.time.nanos");

    // 存储指标属性
    public static final AttributeKey<Attributes> METRICS_ATTRIBUTES =
            AttributeKey.valueOf("otel.metrics.attributes");
}
