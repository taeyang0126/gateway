package com.lei.gateway.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwksKeyProviderTest {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private int serverPort;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicReference<String> jwksBody = new AtomicReference<>(
            "{\"keys\":[]}");

    @BeforeEach
    void setUp() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(1024 * 1024))
                                .addLast(new JwksRequestHandler());
                    }
                });

        serverChannel = bootstrap.bind("127.0.0.1", 0).sync().channel();
        serverPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().sync();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().sync();
        }
    }

    @Test
    void shouldUseCacheAndRefreshWhenKidMissed() throws Exception {
        RSAKey key1 = generateRsaPublicJwk("k1");
        RSAKey key2 = generateRsaPublicJwk("k2");
        jwksBody.set(new JWKSet(key1).toString());

        JwksKeyProvider provider = new JwksKeyProvider();
        EffectiveSecurityConfig.Jwt config = new EffectiveSecurityConfig.Jwt(
                null, null, null,
                "http://127.0.0.1:" + serverPort + "/jwks",
                300, 300, 300);

        JWSVerifier first = provider.resolveVerifier(headerWithKid("k1"), config);
        assertThat(first).isNotNull();
        assertThat(requestCount.get()).isEqualTo(1);

        JWSVerifier second = provider.resolveVerifier(headerWithKid("k1"), config);
        assertThat(second).isNotNull();
        assertThat(requestCount.get()).isEqualTo(1);

        jwksBody.set(new JWKSet(key2).toString());
        JWSVerifier refreshed = provider.resolveVerifier(headerWithKid("k2"), config);
        assertThat(refreshed).isNotNull();
        assertThat(requestCount.get()).isEqualTo(2);
    }

    private static JWSHeader headerWithKid(String kid) {
        return new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(kid)
                .build();
    }

    private static RSAKey generateRsaPublicJwk(String kid) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        return new RSAKey.Builder(publicKey).keyID(kid).build();
    }

    private class JwksRequestHandler
            extends SimpleChannelInboundHandler<FullHttpRequest> {

        @Override
        protected void channelRead0(ChannelHandlerContext context,
                FullHttpRequest request) {
            FullHttpResponse response;
            if (request.method() == HttpMethod.GET
                    && "/jwks".equals(request.uri())) {
                requestCount.incrementAndGet();
                response = jsonResponse(jwksBody.get());
            } else {
                response = plainResponse(HttpResponseStatus.NOT_FOUND, "not found");
            }
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static FullHttpResponse jsonResponse(String body) {
        ByteBuf content = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                content.readableBytes());
        return response;
    }

    private static FullHttpResponse plainResponse(
            HttpResponseStatus status, String body) {
        ByteBuf content = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                content.readableBytes());
        return response;
    }
}
