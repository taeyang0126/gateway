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
package com.lei.java.gateway.server.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import static com.lei.java.gateway.server.metrics.MetricsUtil.PROCESSING_DURATION_HISTOGRAM;
import static com.lei.java.gateway.server.trace.TracingAttributes.METRICS_ATTRIBUTES;
import static com.lei.java.gateway.server.trace.TracingAttributes.SERVER_SPAN_KEY;
import static com.lei.java.gateway.server.trace.TracingAttributes.START_TIME_KEY;

/**
 * <p>
 * TraceOutboundHandler
 * </p>
 *
 * @author 伍磊
 */
public class TraceOutboundHandler extends ChannelOutboundHandlerAdapter {
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("TraceOutboundHandler");

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
        final Span parentSpan = ctx.channel()
                .attr(SERVER_SPAN_KEY)
                .getAndSet(null);
        final Long startTimeNanos = ctx.channel()
                .attr(START_TIME_KEY)
                .getAndSet(null);
        Attributes attributes = ctx.channel()
                .attr(METRICS_ATTRIBUTES)
                .getAndSet(null);

        if (parentSpan != null) {
            promise.addListener(future -> {
                if (startTimeNanos != null) {
                    long durationNanos = System.nanoTime() - startTimeNanos;
                    PROCESSING_DURATION_HISTOGRAM.record(durationNanos / 1_000_000.0, attributes);
                }

                if (!future.isSuccess()) {
                    parentSpan.recordException(future.cause());
                    parentSpan.setStatus(StatusCode.ERROR, "Response write failed");
                }
                parentSpan.end();
            });
        }

        ctx.write(msg, promise);
    }
}
