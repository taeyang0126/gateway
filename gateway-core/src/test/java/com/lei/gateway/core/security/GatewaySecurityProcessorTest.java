package com.lei.gateway.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.ObservabilityProperties;
import com.lei.gateway.core.config.SecurityProperties;
import com.lei.gateway.core.observability.MetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GatewaySecurityProcessorTest {

    @Test
    void buildFiltersShouldFollowFixedOrderWhenSecurityEnabled() throws Exception {
        GatewaySecurityProcessor processor = createProcessor();
        EffectiveSecurityConfig config = createConfig(true);

        List<SecurityFilter> filters = invokeBuildFilters(processor, config);

        assertThat(filterNames(filters)).containsExactly(
                "real-ip",
                "ip-access",
                "ip-rate-limit",
                "auth",
                "user-rate-limit");
    }

    @Test
    void buildFiltersShouldKeepRealIpOnlyWhenSecurityDisabled() throws Exception {
        GatewaySecurityProcessor processor = createProcessor();
        EffectiveSecurityConfig config = createConfig(false);

        List<SecurityFilter> filters = invokeBuildFilters(processor, config);

        assertThat(filterNames(filters)).containsExactly("real-ip");
    }

    private static GatewaySecurityProcessor createProcessor() {
        SecurityProperties properties = new SecurityProperties();
        MetricsCollector metricsCollector = new MetricsCollector(
                new SimpleMeterRegistry(), new ObservabilityProperties());
        return new GatewaySecurityProcessor(properties, metricsCollector);
    }

    private static EffectiveSecurityConfig createConfig(boolean enabled) {
        return new EffectiveSecurityConfig(
                enabled,
                List.of(),
                null,
                new EffectiveSecurityConfig.IpAccess(false, false, true,
                        List.of(), List.of()),
                new EffectiveSecurityConfig.Auth(false, false, true,
                        SecurityProperties.AuthType.JWT,
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
    }

    @SuppressWarnings("unchecked")
    private static List<SecurityFilter> invokeBuildFilters(
            GatewaySecurityProcessor processor, EffectiveSecurityConfig config)
            throws Exception {
        Method method = GatewaySecurityProcessor.class.getDeclaredMethod(
                "buildFilters", EffectiveSecurityConfig.class);
        method.setAccessible(true);
        return (List<SecurityFilter>) method.invoke(processor, config);
    }

    private static List<String> filterNames(List<SecurityFilter> filters) {
        return filters.stream()
                .map(SecurityFilter::name)
                .collect(Collectors.toList());
    }
}
