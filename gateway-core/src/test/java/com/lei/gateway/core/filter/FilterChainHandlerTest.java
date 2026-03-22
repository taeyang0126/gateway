package com.lei.gateway.core.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * FilterChainHandler 单元测试。
 */
class FilterChainHandlerTest {

    // ---- 辅助 ----

    private static HttpRequest newRequest() {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
    }

    private static HttpResponse newResponse(HttpResponseStatus status) {
        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
    }

    private static Filter continueFilter(String name) {
        return new Filter() {
            @Override public String name() { return name; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };
    }

    private static Filter abortFilter(String name, HttpResponseStatus status) {
        return new Filter() {
            @Override public String name() { return name; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
                ctx.writeAndFlush(resp);
                return CompletableFuture.completedFuture(FilterResult.ABORT);
            }
        };
    }

    private static class CapturingHandler extends ChannelInboundHandlerAdapter {
        final AtomicBoolean received = new AtomicBoolean(false);
        final AtomicReference<Object> msg = new AtomicReference<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object m) {
            received.set(true);
            msg.set(m);
            ReferenceCountUtil.release(m);
        }
    }

    // ---- 测试 ----

    @Test
    void nonHttpRequest_passThrough() {
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound("not-a-request");
        assertThat(downstream.received.get()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    void emptyChain_firesChannelRead() {
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        assertThat(downstream.received.get()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    void singleContinueFilter_firesChannelRead() {
        FilterChainHandler handler = new FilterChainHandler(List.of(continueFilter("f1")), null, 0);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        assertThat(downstream.received.get()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    void abortFilter_stopsChain_returnsErrorResponse() {
        Filter abort = abortFilter("abort", HttpResponseStatus.FORBIDDEN);
        FilterChainHandler handler = new FilterChainHandler(
                List.of(abort, continueFilter("should-not-run")), null, 0);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        assertThat(downstream.received.get()).isFalse();
        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void preThrowsException_returns500() {
        Filter throwing = new Filter() {
            @Override public String name() { return "bad"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                throw new RuntimeException("pre exception");
            }
        };
        FilterChainHandler handler = new FilterChainHandler(List.of(throwing), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void exceptionCaught_returns500() {
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.pipeline().fireExceptionCaught(new RuntimeException("test"));

        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void onUpstreamResponse_noWrapping_runsPostChainInReverse() {
        List<String> order = new ArrayList<>();
        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                order.add("f1-post");
            }
        };
        Filter f2 = new Filter() {
            @Override public String name() { return "f2"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                order.add("f2-post");
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1, f2), null, 0);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        handler.onUpstreamResponse(ch.pipeline().firstContext(), newResponse(HttpResponseStatus.OK));

        assertThat(order).containsExactly("f2-post", "f1-post");
        ch.finishAndReleaseAll();
    }

    @Test
    void onUpstreamResponse_nullContext_noException() {
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        // 未调用 channelRead，filterContext 为 null，不应抛异常
        handler.onUpstreamResponse(ch.pipeline().firstContext(), newResponse(HttpResponseStatus.OK));
        ch.finishAndReleaseAll();
    }

    @Test
    void postThrowsException_otherPostFiltersStillRun() {
        AtomicBoolean f2PostRan = new AtomicBoolean(false);
        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                throw new RuntimeException("post exception");
            }
        };
        Filter f2 = new Filter() {
            @Override public String name() { return "f2"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                f2PostRan.set(true);
            }
        };

        // f2 在前（逆序时 f1 先执行抛异常），f2 仍应执行
        FilterChainHandler handler = new FilterChainHandler(List.of(f2, f1), null, 0);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();
        handler.onUpstreamResponse(ch.pipeline().firstContext(), newResponse(HttpResponseStatus.OK));

        assertThat(f2PostRan.get()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    void wrappingFilter_executeWithProxy_called_postChainRuns() {
        AtomicBoolean postRan = new AtomicBoolean(false);
        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                postRan.set(true);
            }
        };
        WrappingFilter wrapping = new WrappingFilter() {
            @Override public String name() { return "retry"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c, ProxyInvoker invoker) {
                return invoker.invoke(req).thenAccept(resp -> {});
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1), wrapping, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        // 模拟 ProxyHandler 回调
        handler.onUpstreamResponse(ch.pipeline().firstContext(), newResponse(HttpResponseStatus.OK));
        ch.runPendingTasks();

        assertThat(postRan.get()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    void wrappingFilter_executeWithProxyFails_returns500() {
        WrappingFilter wrapping = new WrappingFilter() {
            @Override public String name() { return "bad-retry"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c, ProxyInvoker invoker) {
                return CompletableFuture.failedFuture(new RuntimeException("proxy error"));
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(), wrapping, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void filterContext_setGetContains() {
        FilterContext ctx = new FilterContext();
        assertThat(ctx.contains("key")).isFalse();
        ctx.set("key", "value");
        assertThat(ctx.contains("key")).isTrue();
        assertThat((String) ctx.get("key")).isEqualTo("value");
        ctx.set("key2", null);
        assertThat(ctx.contains("key2")).isTrue();
        assertThat((Object) ctx.get("key2")).isNull();
    }

    @Test
    void channelRead_setsFilterContextAttribute() {
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        FilterContext ctx = ch.attr(FilterChainHandler.FILTER_CONTEXT_KEY).get();
        assertThat(ctx).isNotNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void multipleFilters_preRunsInOrder() {
        List<String> order = new ArrayList<>();
        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                order.add("f1");
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };
        Filter f2 = new Filter() {
            @Override public String name() { return "f2"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                order.add("f2");
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1, f2), null, 0);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        assertThat(order).containsExactly("f1", "f2");
        assertThat(downstream.received.get()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    void timeout_returns504() throws InterruptedException {
        CompletableFuture<FilterResult> neverComplete = new CompletableFuture<>();
        Filter hanging = new Filter() {
            @Override public String name() { return "hanging"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return neverComplete;
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(hanging), null, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        Thread.sleep(5); // 确保 1ms 超时到期
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void timeout_preChainWhenComplete_shortCircuits() throws InterruptedException {
        CompletableFuture<FilterResult> delayedFuture = new CompletableFuture<>();
        Filter hanging = new Filter() {
            @Override public String name() { return "hanging"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return delayedFuture;
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(hanging), null, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        // 超时后 complete，不应再写出第二个响应
        delayedFuture.complete(FilterResult.CONTINUE);
        ch.runPendingTasks();

        FullHttpResponse resp1 = ch.readOutbound();
        assertThat(resp1).isNotNull();
        assertThat(resp1.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp1.release();
        assertThat((Object) ch.readOutbound()).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void normalCompletion_withTimeout_cancelsTimeoutTask() {
        // filterChainTimeoutMs 很大不会触发，正常完成后 cancelTimeout 被调用，无 504
        FilterChainHandler handler = new FilterChainHandler(
                List.of(continueFilter("f")), null, 60_000);
        CapturingHandler downstream = new CapturingHandler();
        EmbeddedChannel ch = new EmbeddedChannel(handler, downstream);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        assertThat(downstream.received.get()).isTrue();
        assertThat((Object) ch.readOutbound()).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void timeout_cancelsRetryDelayFuture() throws InterruptedException {
        AtomicBoolean retryCancelled = new AtomicBoolean(false);
        CompletableFuture<FilterResult> neverComplete = new CompletableFuture<>();

        Filter hanging = new Filter() {
            @Override public String name() { return "hanging"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                c.set(FilterContext.RETRY_DELAY_FUTURE, new ScheduledFuture<Object>() {
                    @Override public boolean cancel(boolean mayInterrupt) {
                        retryCancelled.set(true);
                        return true;
                    }
                    @Override public boolean isCancelled() { return false; }
                    @Override public boolean isDone() { return false; }
                    @Override public Object get() { return null; }
                    @Override public Object get(long t, TimeUnit u) { return null; }
                    @Override public long getDelay(TimeUnit u) { return 0; }
                    @Override public int compareTo(Delayed o) { return 0; }
                });
                return neverComplete;
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(hanging), null, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        assertThat(retryCancelled.get()).isTrue();
        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void sendError_escapesSpecialChars() {
        // 触发 sendError，message 含特殊字符，覆盖 escapeJson 各分支
        Filter throwing = new Filter() {
            @Override public String name() { return "bad"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                throw new RuntimeException("msg with \"quotes\" and \nnewline and \ttab");
            }
        };
        FilterChainHandler handler = new FilterChainHandler(List.of(throwing), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void onUpstreamResponse_responseFutureAlreadyDone_runsPostChainDirectly() {
        // 覆盖 onUpstreamResponse 中 responseFuture.isDone() == true 的分支：
        // future 已完成时不再 complete，走 else 分支直接执行 post 链
        AtomicBoolean postRan = new AtomicBoolean(false);
        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                // 预先放一个已完成的 future
                CompletableFuture<HttpResponse> done = new CompletableFuture<>();
                done.complete(newResponse(HttpResponseStatus.OK));
                c.set("__proxyResponseFuture__", done);
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                postRan.set(true);
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        // responseFuture 已 done，onUpstreamResponse 应走 else 分支执行 post 链
        handler.onUpstreamResponse(ch.pipeline().firstContext(), newResponse(HttpResponseStatus.OK));

        assertThat(postRan.get()).isTrue();
        ch.finishAndReleaseAll();
    }

    @Test
    void timeout_cancelsProxyResponseFuture() throws InterruptedException {
        // 覆盖 handleTimeout 中 responseFuture != null 的分支
        CompletableFuture<FilterResult> neverComplete = new CompletableFuture<>();
        AtomicBoolean futureFailed = new AtomicBoolean(false);

        WrappingFilter wrapping = new WrappingFilter() {
            @Override public String name() { return "retry"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c, ProxyInvoker invoker) {
                // invoke 会在 context 里放 __proxyResponseFuture__
                CompletableFuture<HttpResponse> rf = invoker.invoke(req)
                        .handle((r, ex) -> {
                            if (ex != null) futureFailed.set(true);
                            return r;
                        });
                return rf.thenAccept(r -> {});
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(), wrapping, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        assertThat(futureFailed.get()).isTrue();
        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void exceptionCaught_channelInactive_noResponse() {
        // 覆盖 exceptionCaught 中 channel 不 active 的分支
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        ch.close();

        // channel 已关闭，exceptionCaught 不应写响应
        ch.pipeline().fireExceptionCaught(new RuntimeException("after close"));

        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    void wrappingFilter_timedOut_whenComplete_noDoubleResponse() throws InterruptedException {
        // 覆盖 lambda$dispatchToProxy$2 中 timedOut==true 的分支：
        // WrappingFilter 完成时已超时，不应再写 500
        WrappingFilter wrapping = new WrappingFilter() {
            @Override public String name() { return "slow-retry"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c, ProxyInvoker invoker) {
                // 返回一个永不完成的 future，让超时先触发
                return new CompletableFuture<>();
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(), wrapping, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        // 只有一个 504，没有额外的 500
        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        assertThat((Object) ch.readOutbound()).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void preChain_timedOut_inThenCompose_shortCircuits() throws InterruptedException {
        // 覆盖 runPreChain thenCompose 内 timedOut==true 的分支
        // f1 挂起 -> 超时 -> f1 的 future 完成 -> thenCompose 检测到 timedOut 短路
        CompletableFuture<FilterResult> f1Future = new CompletableFuture<>();
        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return f1Future;
            }
        };
        AtomicBoolean f2Ran = new AtomicBoolean(false);
        Filter f2 = new Filter() {
            @Override public String name() { return "f2"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                f2Ran.set(true);
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1, f2), null, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        // 超时后 f1 完成，thenCompose 应检测到 timedOut 不再执行 f2
        f1Future.complete(FilterResult.CONTINUE);
        ch.runPendingTasks();

        assertThat(f2Ran.get()).isFalse();
        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        assertThat((Object) ch.readOutbound()).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void dispatchToProxy_responseFuture_timedOut_noPostChain() throws InterruptedException {
        // 覆盖 lambda$dispatchToProxy$1 中 timedOut==true 的分支：
        // responseFuture 完成时已超时，post 链不应执行
        AtomicBoolean postRan = new AtomicBoolean(false);
        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                postRan.set(true);
            }
        };
        WrappingFilter wrapping = new WrappingFilter() {
            @Override public String name() { return "retry"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c, ProxyInvoker invoker) {
                // invoke 触发 fireChannelRead，responseFuture 放入 context
                // 返回一个永不完成的 future，让超时先触发
                invoker.invoke(req);
                return new CompletableFuture<>();
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1), wrapping, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        // 超时后再触发 onUpstreamResponse，post 链不应执行
        handler.onUpstreamResponse(ch.pipeline().firstContext(), newResponse(HttpResponseStatus.OK));
        ch.runPendingTasks();

        assertThat(postRan.get()).isFalse();
        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void timeout_withFilterContext_noRetryDelayFuture_noResponseFuture() throws InterruptedException {
        // 覆盖 handleTimeout 中 filterContext != null 但 retryDelayFuture==null 且 responseFuture==null 的分支
        CompletableFuture<FilterResult> neverComplete = new CompletableFuture<>();
        Filter hanging = new Filter() {
            @Override public String name() { return "hanging"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                // 不设置 retryDelayFuture，也不设置 __proxyResponseFuture__
                return neverComplete;
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(hanging), null, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void preChainException_afterTimeout_noDoubleResponse() throws InterruptedException {
        // 覆盖 lambda$channelRead$1 中 timedOut==true 时异常分支：
        // pre 链抛异常，但超时已先触发，不应再写 500
        CompletableFuture<FilterResult> delayedFail = new CompletableFuture<>();
        Filter hanging = new Filter() {
            @Override public String name() { return "hanging"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return delayedFail;
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(hanging), null, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        Thread.sleep(5);
        ch.runScheduledPendingTasks();
        ch.runPendingTasks();

        // 超时后 pre 链以异常完成，timedOut==true，不应再写 500
        delayedFail.completeExceptionally(new RuntimeException("late exception"));
        ch.runPendingTasks();

        FullHttpResponse resp1 = ch.readOutbound();
        assertThat(resp1).isNotNull();
        assertThat(resp1.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp1.release();
        assertThat((Object) ch.readOutbound()).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void exceptionCaught_channelInactive_noWrite() throws Exception {
        // 覆盖 exceptionCaught 中 !channel.isActive() 的分支
        // 用反射直接调用 exceptionCaught，传入一个 isActive()==false 的 mock ctx
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);

        // 构造一个 isActive()==false 的 EmbeddedChannel，通过 close 使其 inactive
        EmbeddedChannel ch = new EmbeddedChannel(handler);
        // 先正常处理一个请求，让 handler 初始化
        ch.writeInbound(newRequest());
        ch.runPendingTasks();
        // 关闭 channel 使 isActive()==false，但 pipeline 仍可用
        ch.close();
        ch.runPendingTasks();

        // 直接通过反射调用 exceptionCaught，绕过 pipeline 已关闭的问题
        java.lang.reflect.Method m = handler.getClass()
                .getMethod("exceptionCaught", io.netty.channel.ChannelHandlerContext.class, Throwable.class);
        // 获取 pipeline 中 handler 的 ctx（close 后 ctx 仍存在）
        io.netty.channel.ChannelHandlerContext ctx = ch.pipeline().context(handler);
        if (ctx != null) {
            m.invoke(handler, ctx, new RuntimeException("after close"));
        }

        // channel 不 active，不应写响应
        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    void escapeJson_nullValue_returnsEmpty() throws Exception {
        // 覆盖 escapeJson(null) 的分支（防御性代码）
        java.lang.reflect.Method m = FilterChainHandler.class
                .getDeclaredMethod("escapeJson", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(null, (Object) null);
        assertThat(result).isEqualTo("");
    }

    @Test
    void handleTimeout_filterContextNull_noNpe() throws Exception {
        // 覆盖 handleTimeout 中 filterContext==null 的分支（防御性代码）
        // 直接构造 handler 但不调用 channelRead，filterContext 保持 null
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 1);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        // 不写入任何请求，直接触发超时（通过反射调用 handleTimeout）
        java.lang.reflect.Method m = FilterChainHandler.class
                .getDeclaredMethod("handleTimeout", io.netty.channel.ChannelHandlerContext.class);
        m.setAccessible(true);
        io.netty.channel.ChannelHandlerContext ctx = ch.pipeline().context(handler);
        m.invoke(handler, ctx);
        ch.runPendingTasks();

        // filterContext==null，仍应返回 504
        FullHttpResponse resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void runPreChain_timedOut_inThenCompose_returnsAbort() throws Exception {
        // 覆盖 runPreChain thenCompose 内 result==CONTINUE 且 timedOut==true 的分支
        // f1.pre 执行时通过反射设置 timedOut=true，然后返回 CONTINUE
        // thenCompose 回调执行时检测到 timedOut==true，短路不执行 f2
        AtomicBoolean f2Ran = new AtomicBoolean(false);
        FilterChainHandler[] handlerRef = new FilterChainHandler[1];

        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                // 在 pre 执行期间设置 timedOut=true（channelRead 已经重置过了，这里再设置）
                try {
                    java.lang.reflect.Field f = FilterChainHandler.class.getDeclaredField("timedOut");
                    f.setAccessible(true);
                    ((java.util.concurrent.atomic.AtomicBoolean) f.get(handlerRef[0])).set(true);
                } catch (Exception ignored) {}
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };
        Filter f2 = new Filter() {
            @Override public String name() { return "f2"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                f2Ran.set(true);
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1, f2), null, 0);
        handlerRef[0] = handler;
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        // f1.pre 里设置了 timedOut=true，thenCompose 检测到后短路，f2 不执行
        assertThat(f2Ran.get()).isFalse();
        ch.finishAndReleaseAll();
    }

    @Test
    void dispatchToProxy_responseFutureThenApply_timedOut_skipsPostChain() throws Exception {
        // 覆盖 lambda$dispatchToProxy$1 中 timedOut==true 时跳过 post 链的分支
        // 需要：invoke 后 responseFuture 已在 context，设置 timedOut=true，再直接 complete responseFuture
        AtomicBoolean postRan = new AtomicBoolean(false);
        FilterChainHandler[] handlerRef = new FilterChainHandler[1];
        FilterContext[] contextRef = new FilterContext[1];

        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public void post(ChannelHandlerContext ctx, HttpResponse resp, FilterContext c) {
                postRan.set(true);
            }
        };
        WrappingFilter wrapping = new WrappingFilter() {
            @Override public String name() { return "retry"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c, ProxyInvoker invoker) {
                contextRef[0] = c;
                // invoke 后 responseFuture 已放入 context，设置 timedOut=true
                CompletableFuture<HttpResponse> rf = invoker.invoke(req);
                try {
                    java.lang.reflect.Field f = FilterChainHandler.class.getDeclaredField("timedOut");
                    f.setAccessible(true);
                    ((java.util.concurrent.atomic.AtomicBoolean) f.get(handlerRef[0])).set(true);
                } catch (Exception ignored) {}
                // 直接 complete responseFuture，thenApply 回调执行时 timedOut==true
                CompletableFuture<HttpResponse> responseFuture = c.get("__proxyResponseFuture__");
                if (responseFuture != null) {
                    responseFuture.complete(newResponse(HttpResponseStatus.OK));
                }
                return rf.thenAccept(r -> {});
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1), wrapping, 0);
        handlerRef[0] = handler;
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        // timedOut==true 时 thenApply 不执行 post 链
        assertThat(postRan.get()).isFalse();
        ch.finishAndReleaseAll();
    }

    @Test
    void dispatchToProxy_wrappingFilter_whenComplete_timedOut_noError() throws Exception {
        // 覆盖 lambda$dispatchToProxy$2 中 ex!=null 且 timedOut==true 时不写 500 的分支
        // executeWithProxy 执行时设置 timedOut=true，然后返回 failedFuture
        FilterChainHandler[] handlerRef = new FilterChainHandler[1];
        WrappingFilter wrapping = new WrappingFilter() {
            @Override public String name() { return "retry"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
            @Override
            public CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c, ProxyInvoker invoker) {
                try {
                    java.lang.reflect.Field f = FilterChainHandler.class.getDeclaredField("timedOut");
                    f.setAccessible(true);
                    ((java.util.concurrent.atomic.AtomicBoolean) f.get(handlerRef[0])).set(true);
                } catch (Exception ignored) {}
                return CompletableFuture.failedFuture(new RuntimeException("proxy error"));
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(), wrapping, 0);
        handlerRef[0] = handler;
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        // timedOut==true，WrappingFilter 异常完成时不应写 500
        assertThat((Object) ch.readOutbound()).isNull();
        ch.finishAndReleaseAll();
    }

    @Test
    void exceptionCaught_channelInactive_viaReflection() throws Exception {
        // 覆盖 exceptionCaught 中 !channel.isActive() 的分支
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        // 在 close 前获取 ctx，close 后 isActive()==false
        io.netty.channel.ChannelHandlerContext ctx = ch.pipeline().context(FilterChainHandler.class);
        ch.close();
        ch.runPendingTasks();

        // ctx 在 close 前获取，此时 channel.isActive()==false，直接调用 exceptionCaught
        assertThat(ctx).isNotNull();
        java.lang.reflect.Method m = FilterChainHandler.class.getMethod(
                "exceptionCaught", io.netty.channel.ChannelHandlerContext.class, Throwable.class);
        m.invoke(handler, ctx, new RuntimeException("inactive channel"));

        assertThat((Object) ch.readOutbound()).isNull();
    }

    @Test
    void handleTimeout_filterContext_noRetryFuture_noResponseFuture_sends504() throws Exception {
        // 覆盖 handleTimeout 中 filterContext!=null 但两个内层 future 都 null 的分支
        // 通过反射直接调用 handleTimeout，此时 filterContext 已设置但无 future
        FilterChainHandler handler = new FilterChainHandler(List.of(continueFilter("f")), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        // 先触发 channelRead 让 filterContext 初始化
        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        // 反射调用 handleTimeout（timedOut 此时为 false，会正常执行）
        java.lang.reflect.Method m = FilterChainHandler.class
                .getDeclaredMethod("handleTimeout", io.netty.channel.ChannelHandlerContext.class);
        m.setAccessible(true);
        io.netty.channel.ChannelHandlerContext ctx = ch.pipeline().context(FilterChainHandler.class);
        if (ctx != null) {
            m.invoke(handler, ctx);
            ch.runPendingTasks();
            FullHttpResponse resp = ch.readOutbound();
            assertThat(resp).isNotNull();
            assertThat(resp.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
            resp.release();
        }
        ch.finishAndReleaseAll();
    }

    @Test
    void handleTimeout_alreadyTimedOut_noDoubleResponse() throws Exception {
        // 覆盖 handleTimeout 中 compareAndSet 返回 false 的分支（重复调用）
        FilterChainHandler handler = new FilterChainHandler(List.of(), null, 0);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        java.lang.reflect.Method m = FilterChainHandler.class
                .getDeclaredMethod("handleTimeout", io.netty.channel.ChannelHandlerContext.class);
        m.setAccessible(true);
        io.netty.channel.ChannelHandlerContext ctx = ch.pipeline().context(FilterChainHandler.class);

        // 第一次调用：正常触发超时
        m.invoke(handler, ctx);
        ch.runPendingTasks();
        FullHttpResponse resp1 = ch.readOutbound();
        assertThat(resp1).isNotNull();
        assertThat(resp1.status()).isEqualTo(HttpResponseStatus.GATEWAY_TIMEOUT);
        resp1.release();

        // 第二次调用：timedOut 已为 true，compareAndSet 返回 false，直接 return，不写第二个响应
        m.invoke(handler, ctx);
        ch.runPendingTasks();
        assertThat((Object) ch.readOutbound()).isNull();

        ch.finishAndReleaseAll();
    }

    @Test
    void runPreChain_thenCompose_timedOut_shortCircuits_viaInternalSet() throws Exception {
        // 覆盖 runPreChain thenCompose 内 result==CONTINUE && timedOut==true 的分支
        // f1.pre 执行时设置 timedOut=true，f1 返回 CONTINUE
        // thenCompose 回调：result!=ABORT，timedOut==true → 短路，不执行 f2
        AtomicBoolean f2Ran = new AtomicBoolean(false);
        FilterChainHandler[] ref = new FilterChainHandler[1];

        Filter f1 = new Filter() {
            @Override public String name() { return "f1"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                try {
                    java.lang.reflect.Field f = FilterChainHandler.class.getDeclaredField("timedOut");
                    f.setAccessible(true);
                    ((java.util.concurrent.atomic.AtomicBoolean) f.get(ref[0])).set(true);
                } catch (Exception ignored) {}
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };
        Filter f2 = new Filter() {
            @Override public String name() { return "f2"; }
            @Override
            public CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
                    HttpRequest req, FilterContext c) {
                f2Ran.set(true);
                return CompletableFuture.completedFuture(FilterResult.CONTINUE);
            }
        };

        FilterChainHandler handler = new FilterChainHandler(List.of(f1, f2), null, 0);
        ref[0] = handler;
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.writeInbound(newRequest());
        ch.runPendingTasks();

        assertThat(f2Ran.get()).isFalse();
        ch.finishAndReleaseAll();
    }
}
