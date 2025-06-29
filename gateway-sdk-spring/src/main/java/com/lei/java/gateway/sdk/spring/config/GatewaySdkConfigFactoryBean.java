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
package com.lei.java.gateway.sdk.spring.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.yaml.snakeyaml.Yaml;

import com.lei.java.gateway.common.config.nacos.NacosConfig;

/**
 * <p>
 * GatewaySdkConfigFactoryBean
 * </p>
 *
 * @author 伍磊
 */
public class GatewaySdkConfigFactoryBean
        implements FactoryBean<GatewaySdkConfig>, InitializingBean, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(GatewaySdkConfigFactoryBean.class);

    private final NacosConfig nacosConfig;
    private ConfigService configService;
    private GatewaySdkConfig gatewaySdkConfig;

    public GatewaySdkConfigFactoryBean(NacosConfig nacosConfig) {
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
    }

    private void updateGatewayConfig(String configStr) {
        Yaml yaml = new Yaml();
        this.gatewaySdkConfig = yaml.loadAs(configStr, GatewaySdkConfig.class);
        logger.info("Gateway config updated: {}", this.gatewaySdkConfig);
    }

    @Override
    public void destroy() throws Exception {
        if (this.configService != null) {
            logger.info("Removing Nacos listener and shutting down config service...");
            this.configService.shutDown();
        }
    }

    @Override
    public GatewaySdkConfig getObject() throws Exception {
        return this.gatewaySdkConfig;
    }

    @Override
    public Class<?> getObjectType() {
        return GatewaySdkConfig.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
