package com.lei.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(new CidrMatcher());

    @Test
    void shouldUseRemoteIpWhenProxyNotTrusted() {
        ChannelHandlerContext ctx = mockContext("10.0.0.2");
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("X-Forwarded-For", "1.2.3.4");

        String ip = resolver.resolve(ctx, headers, List.of("192.168.0.0/16"), null);
        assertThat(ip).isEqualTo("10.0.0.2");
    }

    @Test
    void shouldUseRightMostUntrustedAddressWhenProxyTrusted() {
        ChannelHandlerContext ctx = mockContext("127.0.0.1");
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("X-Forwarded-For", "1.2.3.4, 127.0.0.2, 127.0.0.3");

        String ip = resolver.resolve(ctx, headers,
                List.of("127.0.0.0/24"), null);
        assertThat(ip).isEqualTo("1.2.3.4");
    }

    @Test
    void shouldFallbackToLeftMostWhenEntireForwardChainTrusted() {
        ChannelHandlerContext ctx = mockContext("127.0.0.1");
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("X-Forwarded-For", "127.0.0.2, 127.0.0.3");

        String ip = resolver.resolve(ctx, headers,
                List.of("127.0.0.0/24"), null);
        assertThat(ip).isEqualTo("127.0.0.2");
    }

    @Test
    void shouldFallbackToRemoteWhenNoForwardHeader() {
        ChannelHandlerContext ctx = mockContext("127.0.0.1");
        HttpHeaders headers = new DefaultHttpHeaders();

        String ip = resolver.resolve(ctx, headers, List.of("127.0.0.1/32"), null);
        assertThat(ip).isEqualTo("127.0.0.1");
    }

    @Test
    void shouldUseForwardedChainWhenProxyTrustedAndXffMissing() {
        ChannelHandlerContext ctx = mockContext("127.0.0.1");
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("Forwarded",
                        "for=198.51.100.2;proto=https, for=127.0.0.2;by=127.0.0.1");

        String ip = resolver.resolve(ctx, headers,
                List.of("127.0.0.0/24"), null);
        assertThat(ip).isEqualTo("198.51.100.2");
    }

    @Test
    void shouldResolveByTrustedProxyHopsWhenConfigured() {
        ChannelHandlerContext ctx = mockContext("10.10.10.10");
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("X-Forwarded-For", "1.2.3.4, 5.6.7.8");

        String ip = resolver.resolve(ctx, headers, List.of(), 1);
        assertThat(ip).isEqualTo("1.2.3.4");
    }

    @Test
    void shouldFallbackToRemoteWhenTrustedProxyHopsInsufficient() {
        ChannelHandlerContext ctx = mockContext("10.10.10.10");
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("X-Forwarded-For", "1.2.3.4");

        String ip = resolver.resolve(ctx, headers, List.of(), 2);
        assertThat(ip).isEqualTo("10.10.10.10");
    }

    @Test
    void shouldValidateRemoteIpAgainstTrustedProxiesInHopsMode() {
        ChannelHandlerContext ctx = mockContext("10.10.10.10");
        HttpHeaders headers = new DefaultHttpHeaders()
                .add("X-Forwarded-For", "1.2.3.4, 5.6.7.8");

        String ip = resolver.resolve(ctx, headers,
                List.of("192.168.0.0/16"), 1);
        assertThat(ip).isEqualTo("10.10.10.10");
    }

    private static ChannelHandlerContext mockContext(String remoteIp) {
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        Channel channel = mock(Channel.class);
        when(ctx.channel()).thenReturn(channel);
        when(channel.remoteAddress()).thenReturn(
                new InetSocketAddress(remoteIp, 18080));
        return ctx;
    }
}
