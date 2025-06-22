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
package com.lei.java.gateway.server.config;

import com.alibaba.nacos.api.exception.NacosException;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.lei.java.gateway.common.config.nacos.NacosConfig;
import com.lei.java.gateway.common.config.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.GatewayServer;
import com.lei.java.gateway.server.auth.AuthService;
import com.lei.java.gateway.server.auth.DefaultAuthService;
import com.lei.java.gateway.server.handler.AuthHandler;
import com.lei.java.gateway.server.route.DefaultRouteService;
import com.lei.java.gateway.server.route.RouteService;
import com.lei.java.gateway.server.route.ServiceRegistry;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.loadbalancer.LoadBalancer;
import com.lei.java.gateway.server.route.loadbalancer.RandomLoadBalancer;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;
import com.lei.java.gateway.server.session.DistributedSessionManager;
import com.lei.java.gateway.server.session.SessionManager;

@Configuration
@ComponentScan(value = "com.lei.java.gateway.server")
public class GatewayConfiguration {
    @Bean
    public NacosConfig nacosConfig() {
        return NacosConfigLoader.load();
    }

    @Bean("gatewayConfig")
    public GatewayConfigFactoryBean gatewayConfigFactoryBean(NacosConfig nacosConfig) {
        return new GatewayConfigFactoryBean(nacosConfig);
    }

    @Bean
    public ConnectionManager connectionManager() {
        return new DefaultConnectionManager();
    }

    @Bean
    public ServiceRegistry serviceRegistry(NacosConfig nacosConfig) throws NacosException {
        return new NacosServiceRegistry(nacosConfig);
    }

    @Bean
    public SessionManager sessionManager(RedissonClient redissonClient) {
        return new DistributedSessionManager(redissonClient);
    }

    @Bean
    public LoadBalancer loadBalancer() {
        return new RandomLoadBalancer();
    }

    @Bean
    public RouteService routeService(
            ServiceRegistry serviceRegistry,
            LoadBalancer loadBalancer,
            ConnectionManager connectionManager) {
        return new DefaultRouteService(serviceRegistry, loadBalancer, connectionManager);
    }

    @Bean
    public AuthService authService() {
        return new DefaultAuthService();
    }

    @Bean
    public AuthHandler authHandler(AuthService authService, SessionManager sessionManager) {
        return new AuthHandler(authService, sessionManager);
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(GatewayConfig gatewayConfig) {
        Config config = new Config();
        GatewayConfig.RedisConfig redisConfig = gatewayConfig.getRedis();

        config.useSingleServer()
                .setAddress("redis://"
                        + redisConfig.getHost()
                        + ":"
                        + redisConfig.getPort())
                .setPassword(redisConfig.getPassword())
                .setDatabase(redisConfig.getDatabase())
                .setConnectionPoolSize(redisConfig.getConnectionPoolSize())
                .setConnectionMinimumIdleSize(redisConfig.getConnectionMinimumIdleSize())
                .setConnectTimeout(redisConfig.getConnectTimeout());

        config.setCodec(new JsonJacksonCodec());

        return Redisson.create(config);
    }

    @Bean
    public GatewayServer gatewayServer(
            GatewayConfig gatewayConfig,
            SessionManager sessionManager,
            RouteService routeService,
            AuthHandler authHandler,
            ServiceRegistry registry,
            RedissonClient redissonClient) {
        return new GatewayServer(
                gatewayConfig.getServer()
                        .getPort(),
                sessionManager,
                routeService,
                authHandler,
                registry,
                redissonClient);
    }
}