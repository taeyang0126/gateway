/*
 * Copyright (c) 2026 lei.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lei.java.gateway.server.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.lei.java.gateway.server.bootstrap.GatewayBootstrap;
import com.lei.java.gateway.server.config.GatewayServerConfig;
import com.lei.java.gateway.server.config.GatewayServerConfigFactory;
import com.lei.java.gateway.server.config.GatewayServerProperties;
import com.lei.java.gateway.server.http.DefaultErrorResponseMapper;
import com.lei.java.gateway.server.http.DefaultHeaderPolicyService;
import com.lei.java.gateway.server.http.ErrorResponseMapper;
import com.lei.java.gateway.server.http.HeaderPolicyService;
import com.lei.java.gateway.server.logging.AccessLogService;
import com.lei.java.gateway.server.logging.DefaultAccessLogService;
import com.lei.java.gateway.server.proxy.DefaultTimeoutPolicy;
import com.lei.java.gateway.server.proxy.TimeoutPolicy;
import com.lei.java.gateway.server.routing.RouteService;
import com.lei.java.gateway.server.routing.StaticRouteService;

/** 基于 Spring Boot 的网关服务启动入口。 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayServerProperties.class)
public class GatewayServerMain {

    /**
     * 启动 Spring 容器并自动拉起网关。
     *
     * @param args 启动参数
     */
    public static void main(final String[] args) {
        final SpringApplication application = new SpringApplication(GatewayServerMain.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }

    @Bean
    public RouteService routeService() {
        return new StaticRouteService();
    }

    @Bean
    public HeaderPolicyService headerPolicyService() {
        return new DefaultHeaderPolicyService();
    }

    @Bean
    public TimeoutPolicy timeoutPolicy() {
        return new DefaultTimeoutPolicy();
    }

    @Bean
    public ErrorResponseMapper errorResponseMapper() {
        return new DefaultErrorResponseMapper();
    }

    @Bean
    public AccessLogService accessLogService() {
        return new DefaultAccessLogService();
    }

    @Bean
    public GatewayBootstrap gatewayBootstrap(
            final RouteService routeService,
            final HeaderPolicyService headerPolicyService,
            final TimeoutPolicy timeoutPolicy,
            final ErrorResponseMapper errorResponseMapper,
            final AccessLogService accessLogService) {
        return new GatewayBootstrap(
                routeService,
                headerPolicyService,
                timeoutPolicy,
                errorResponseMapper,
                accessLogService);
    }

    @Bean
    public GatewayServerConfigFactory gatewayServerConfigFactory() {
        return new GatewayServerConfigFactory();
    }

    @Bean
    public GatewayServerConfig gatewayServerConfig(
            final GatewayServerConfigFactory factory, final GatewayServerProperties properties) {
        return factory.create(properties);
    }

    @Bean
    public GatewayServerLifecycle gatewayServerLifecycle(
            final GatewayBootstrap gatewayBootstrap,
            final GatewayServerConfig gatewayServerConfig) {
        return new GatewayServerLifecycle(gatewayBootstrap, gatewayServerConfig);
    }
}
