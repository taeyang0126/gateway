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
package com.lei.java.gateway.server.metrics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

import com.lei.java.gateway.server.session.SessionManager;

/**
 * <p>
 * MetricsUtil
 * </p>
 *
 * @author 伍磊
 */
public final class MetricsUtil {
    private static final Meter METER = GlobalOpenTelemetry.getMeter("gateway-server");

    /**
     * 计数器：记录接收到的消息总数 .setUnit("1") 表示单位是“个” .setDescription(...) 添加描述信息
     */
    public static final LongCounter MESSAGES_RECEIVED_COUNTER =
            METER.counterBuilder("gateway.messages.received.total")
                    .setUnit("1")
                    .setDescription("Total number of messages received by the gateway")
                    .build();

    /**
     * 直方图：记录消息处理的端到端延迟 .setUnit("ms") 表示单位是“毫秒”
     */
    public static final DoubleHistogram PROCESSING_DURATION_HISTOGRAM =
            METER.histogramBuilder("gateway.message.processing.duration")
                    .setUnit("ms")
                    .setDescription("End-to-end processing duration of a message")
                    .build();

    /**
     * 仪表盘 (Gauge)：实时观测当前活跃的会话数 这个比较特殊，它通过一个回调函数来异步地获取值
     *
     * @param sessionManager 会话管理器实例
     */
    public static void registerActiveSessionsGauge(SessionManager sessionManager) {
        METER.gaugeBuilder("gateway.sessions.active")
                .setDescription("Current number of active sessions")
                .buildWithCallback(measurement -> measurement
                        .record(sessionManager.getActiveSessionCount(), Attributes.empty()));
    }
}
