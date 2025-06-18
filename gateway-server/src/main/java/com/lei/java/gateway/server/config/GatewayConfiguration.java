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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import com.lei.java.gateway.server.GatewayServer;
import com.lei.java.gateway.server.config.nacos.NacosConfig;
import com.lei.java.gateway.server.config.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.route.ServiceRegistry;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;

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
    public GatewayServer gatewayServer(
            GatewayConfig gatewayConfig,
            ConnectionManager connectionManager,
            ServiceRegistry serviceRegistry) {
        return new GatewayServer(
                gatewayConfig.getServer()
                        .getPort(),
                serviceRegistry,
                connectionManager);
    }
}