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

import java.util.concurrent.Executor;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.yaml.snakeyaml.Yaml;

import com.lei.java.gateway.server.config.nacos.NacosConfig;

/**
 * <p>
 * GatewayConfigFactoryBean
 * </p>
 *
 * @author 伍磊
 */
public class GatewayConfigFactoryBean
        implements FactoryBean<GatewayConfig>, InitializingBean, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(GatewayConfigFactoryBean.class);
    private final NacosConfig nacosConfig;
    private ConfigService configService;
    private GatewayConfig gatewayConfig;
    private Listener configListener;

    public GatewayConfigFactoryBean(NacosConfig nacosConfig) {
        this.nacosConfig = nacosConfig;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.configService = NacosFactory.createConfigService(nacosConfig.buildProperties());
        String initialConfig =
                configService.getConfig(nacosConfig.getDataId(), nacosConfig.getGroup(), 5000);
        if (initialConfig == null) {
            throw new IllegalStateException(
                    "Could not load config from Nacos, dataId="
                            + nacosConfig.getDataId());
        }
        // 更新配置
        updateGatewayConfig(initialConfig);

        // 添加监听器以实现动态刷新
        this.configListener = new Listener() {
            @Override
            public Executor getExecutor() {
                return null; // 使用默认线程池
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                System.out.println("Nacos config changed, reloading...");
                updateGatewayConfig(configInfo);
            }
        };
        configService
                .addListener(nacosConfig.getDataId(), nacosConfig.getGroup(), this.configListener);
    }

    private void updateGatewayConfig(String configStr) {
        Yaml yaml = new Yaml();
        this.gatewayConfig = yaml.loadAs(configStr, GatewayConfig.class);
        logger.info("Gateway config updated: {}", this.gatewayConfig);
    }

    @Override
    public void destroy() throws Exception {
        if (this.configService != null) {
            logger.info("Removing Nacos listener and shutting down config service...");
            this.configService.removeListener(nacosConfig.getDataId(),
                    nacosConfig.getGroup(),
                    this.configListener);
            this.configService.shutDown();
        }
    }

    @Override
    public GatewayConfig getObject() throws Exception {
        return this.gatewayConfig;
    }

    @Override
    public Class<?> getObjectType() {
        return GatewayConfig.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
