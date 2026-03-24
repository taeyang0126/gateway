package com.lei.gateway.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.SecurityProperties;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SecurityAuditLoggerTest {

    @Test
    void shouldLogStructuredAuditFields() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("security-audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);

        try {
            SecurityRequestContext context = createContext();
            context.setClientIp("10.1.2.3");

            SecurityDecision decision = SecurityDecision.deny(
                    HttpResponseStatus.FORBIDDEN,
                    "ip-access",
                    "ip_in_deny_list");

            new SecurityAuditLogger().log(context, decision);

            assertThat(appender.list).hasSize(1);
            String message = appender.list.get(0).getFormattedMessage();
            assertThat(message).contains("traceId=t-123");
            assertThat(message).contains("clientIp=10.1.2.3");
            assertThat(message).contains("routeId=route-1");
            assertThat(message).contains("filterName=ip-access");
            assertThat(message).contains("decision=DENY");
            assertThat(message).contains("reason=ip_in_deny_list");
        } finally {
            auditLogger.detachAppender(appender);
        }
    }

    @Test
    void shouldLogStructuredAuditFieldsForAllowDecision() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("security-audit");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);

        try {
            SecurityRequestContext context = createContext();
            context.setClientIp("10.1.2.3");

            SecurityDecision decision = SecurityDecision.allow("auth", "authenticated");

            new SecurityAuditLogger().log(context, decision);

            assertThat(appender.list).hasSize(1);
            String message = appender.list.get(0).getFormattedMessage();
            assertThat(message).contains("traceId=t-123");
            assertThat(message).contains("clientIp=10.1.2.3");
            assertThat(message).contains("routeId=route-1");
            assertThat(message).contains("filterName=auth");
            assertThat(message).contains("decision=ALLOW");
            assertThat(message).contains("reason=authenticated");
        } finally {
            auditLogger.detachAppender(appender);
        }
    }

    private static SecurityRequestContext createContext() {
        Route route = new Route();
        route.setId("route-1");
        route.setPathPrefix("/api/**");
        route.setUpstream("http://localhost:8081");

        EffectiveSecurityConfig config = new EffectiveSecurityConfig(
                true,
                java.util.List.of(),
                null,
                new EffectiveSecurityConfig.IpAccess(
                        false, false, true,
                        java.util.List.of(), java.util.List.of()),
                new EffectiveSecurityConfig.Auth(
                        false, false, true, SecurityProperties.AuthType.JWT,
                        new EffectiveSecurityConfig.TokenExtractor(
                                "Authorization", "Bearer "),
                        new EffectiveSecurityConfig.Providers(
                                new EffectiveSecurityConfig.Jwt(
                                        null, null, null, null, 300, 500, 1000))),
                new EffectiveSecurityConfig.RateLimit(
                        new EffectiveSecurityConfig.IpRateLimit(false, false,
                                SecurityProperties.RateLimitMode.LOCAL,
                                100, 100, 50),
                        new EffectiveSecurityConfig.UserRateLimit(false, false,
                                SecurityProperties.RateLimitMode.LOCAL,
                                100, 100, 50)));

        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/demo");
        ChannelHandlerContext channelHandlerContext = mock(ChannelHandlerContext.class);
        return new SecurityRequestContext(channelHandlerContext,
                request, route, config, "t-123");
    }
}
