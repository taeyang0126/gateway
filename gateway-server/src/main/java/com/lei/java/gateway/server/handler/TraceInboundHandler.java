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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import com.lei.java.gateway.common.protocol.GatewayMessage;

import static com.lei.java.gateway.server.trace.TracingAttributes.SERVER_SPAN_KEY;

/**
 * <p>
 * TraceInboundHandler
 * </p>
 *
 * @author 伍磊
 */
public class TraceInboundHandler extends ChannelInboundHandlerAdapter {
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("TraceInboundHandler");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Span parentSpan = TRACER.spanBuilder("Process Gateway Request")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("net.peer.address",
                        ctx.channel()
                                .remoteAddress()
                                .toString())
                .startSpan();
        if (msg instanceof GatewayMessage message) {
            parentSpan.setAttribute("message.type", String.valueOf(message.getMsgType()));
            parentSpan.setAttribute("client.id", message.getClientId());
            parentSpan.setAttribute("request.id", String.valueOf(message.getRequestId()));
        }

        ctx.channel()
                .attr(SERVER_SPAN_KEY)
                .set(parentSpan);

        try (Scope scope = parentSpan.makeCurrent()) {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Span parentSpan = ctx.channel()
                .attr(SERVER_SPAN_KEY)
                .getAndSet(null);
        if (parentSpan != null) {
            parentSpan.recordException(cause);
            parentSpan.setStatus(StatusCode.ERROR, "An exception occurred in the pipeline");
            parentSpan.end();
        }
        ctx.fireExceptionCaught(cause);
    }
}
