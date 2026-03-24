package com.lei.gateway.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.lei.gateway.core.config.Route;
import com.lei.gateway.core.config.RouteSecurityProperties;
import com.lei.gateway.core.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class RouteSecurityConfigResolverTest {

    @Test
    void mergeModeShouldOverlayRouteFieldsOnly() {
        SecurityProperties global = createGlobal();
        Route route = createRouteWithMergeOverride();

        RouteSecurityConfigResolver resolver =
                new RouteSecurityConfigResolver(global);
        EffectiveSecurityConfig resolved = resolver.resolve(route);

        assertThat(resolved.isEnabled()).isTrue();
        assertThat(resolved.getIpAccess().isEnabled()).isTrue();
        assertThat(resolved.getIpAccess().getAllowList())
                .containsExactly("192.168.1.0/24");
        assertThat(resolved.getIpAccess().getDenyList())
                .containsExactly("10.0.0.0/8");
        assertThat(resolved.getAuth().isEnabled()).isTrue();
        assertThat(resolved.getAuth().getProviders().getJwt().getIssuer())
                .isEqualTo("route-issuer");
        assertThat(resolved.getAuth().getProviders().getJwt().getAudience())
                .isEqualTo("global-audience");
        assertThat(resolved.getTrustedProxyHops()).isEqualTo(2);
    }

    @Test
    void replaceModeShouldNotInheritGlobalFields() {
        SecurityProperties global = createGlobal();

        Route route = new Route();
        route.setId("r1");
        route.setPathPrefix("/api/**");
        route.setUpstream("http://localhost:8081");

        RouteSecurityProperties routeSecurity = new RouteSecurityProperties();
        routeSecurity.setMode(SecurityProperties.MergeMode.REPLACE);
        routeSecurity.setEnabled(true);

        RouteSecurityProperties.RouteAuthProperties auth =
                new RouteSecurityProperties.RouteAuthProperties();
        auth.setEnabled(true);
        auth.setType(SecurityProperties.AuthType.JWT);
        routeSecurity.setAuth(auth);
        route.setSecurity(routeSecurity);

        RouteSecurityConfigResolver resolver =
                new RouteSecurityConfigResolver(global);
        EffectiveSecurityConfig resolved = resolver.resolve(route);

        assertThat(resolved.getTrustedProxies()).isEmpty();
        assertThat(resolved.getTrustedProxyHops()).isNull();
        assertThat(resolved.getIpAccess().isEnabled()).isFalse();
        assertThat(resolved.getRateLimit().getIp().isEnabled()).isFalse();
        assertThat(resolved.getRateLimit().getUser().isEnabled()).isFalse();
        assertThat(resolved.getAuth().isEnabled()).isTrue();
    }

    private static SecurityProperties createGlobal() {
        SecurityProperties global = new SecurityProperties();
        global.setEnabled(true);
        global.setTrustedProxies(java.util.List.of("127.0.0.1/32"));
        global.setTrustedProxyHops(1);
        global.getIpAccess().setEnabled(true);
        global.getIpAccess().setAllowList(java.util.List.of("172.16.0.0/16"));
        global.getIpAccess().setDenyList(java.util.List.of("10.0.0.0/8"));
        global.getAuth().setEnabled(true);
        global.getAuth().getProviders().getJwt().setIssuer("global-issuer");
        global.getAuth().getProviders().getJwt().setAudience("global-audience");
        global.getRateLimit().getIp().setEnabled(true);
        global.getRateLimit().getUser().setEnabled(true);
        return global;
    }

    private static Route createRouteWithMergeOverride() {
        Route route = new Route();
        route.setId("r1");
        route.setPathPrefix("/api/**");
        route.setUpstream("http://localhost:8081");

        RouteSecurityProperties routeSecurity = new RouteSecurityProperties();
        routeSecurity.setMode(SecurityProperties.MergeMode.MERGE);
        routeSecurity.setTrustedProxyHops(2);

        RouteSecurityProperties.RouteIpAccessProperties ip =
                new RouteSecurityProperties.RouteIpAccessProperties();
        ip.setAllowList(java.util.List.of("192.168.1.0/24"));
        routeSecurity.setIpAccess(ip);

        RouteSecurityProperties.RouteAuthProperties auth =
                new RouteSecurityProperties.RouteAuthProperties();
        RouteSecurityProperties.RouteJwtProperties jwt =
                new RouteSecurityProperties.RouteJwtProperties();
        jwt.setIssuer("route-issuer");
        RouteSecurityProperties.RouteAuthProvidersProperties providers =
                new RouteSecurityProperties.RouteAuthProvidersProperties();
        providers.setJwt(jwt);
        auth.setProviders(providers);
        routeSecurity.setAuth(auth);
        route.setSecurity(routeSecurity);
        return route;
    }
}
