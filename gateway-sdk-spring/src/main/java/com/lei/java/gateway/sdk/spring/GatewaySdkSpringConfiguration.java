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
package com.lei.java.gateway.sdk.spring;

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
import com.lei.java.gateway.common.config.redis.RedisConfig;
import com.lei.java.gateway.sdk.core.message.GenericMessageDispatcher;
import com.lei.java.gateway.sdk.core.message.MessageDispatcher;
import com.lei.java.gateway.sdk.core.route.ClientGatewayLocator;
import com.lei.java.gateway.sdk.core.route.GenericClientGatewayLocator;
import com.lei.java.gateway.sdk.spring.config.GatewaySdkConfig;
import com.lei.java.gateway.sdk.spring.config.GatewaySdkConfigFactoryBean;

/**
 * <p>
 * GatewaySdkSpringConfiguration
 * </p>
 *
 * @author 伍磊
 */
@Configuration
@ComponentScan(value = "com.lei.java.gateway.sdk.spring")
public class GatewaySdkSpringConfiguration {
    @Bean
    public NacosConfig nacosConfig() {
        return NacosConfigLoader.load();
    }

    @Bean("gatewaySdkConfig")
    public GatewaySdkConfigFactoryBean gatewayConfigFactoryBean(NacosConfig nacosConfig) {
        return new GatewaySdkConfigFactoryBean(nacosConfig);
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(GatewaySdkConfig gatewaySdkConfig) {
        Config config = new Config();
        RedisConfig redisConfig = gatewaySdkConfig.getRedis();

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
    public ClientGatewayLocator clientGatewayLocator(
            RedissonClient redissonClient,
            NacosConfig nacosConfig) throws NacosException {
        return new GenericClientGatewayLocator(redissonClient, nacosConfig);
    }

    @Bean
    public MessageDispatcher messageDispatcher(ClientGatewayLocator clientGatewayLocator) {
        return new GenericMessageDispatcher(clientGatewayLocator);
    }
}
