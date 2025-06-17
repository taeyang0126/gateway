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
package com.lei.java.gateway.server.server;

import java.util.concurrent.TimeUnit;

import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.server.GatewayServer;
import com.lei.java.gateway.server.route.ServiceRegistry;
import com.lei.java.gateway.server.route.connection.ConnectionManager;
import com.lei.java.gateway.server.route.connection.DefaultConnectionManager;
import com.lei.java.gateway.server.route.nacos.NacosConfig;
import com.lei.java.gateway.server.route.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;

/**
 * <p>
 * 代理 httpbin
 * </p>
 *
 * @author 伍磊
 */
public class HttpBinProxyNacosIT extends AbstractHttpBinProxyTest {

    private static final Logger logger = LoggerFactory.getLogger(HttpBinProxyNacosIT.class);

    private static final int PORT = 8888;

    @Override
    protected int getPort() {
        return PORT;
    }

    @Override
    protected GatewayServer getGatewayServer() {
        logger.info("Nacos container started at: {}", NACOS_CONTAINER.getServerAddr());
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        ConnectionManager connectionManager = new DefaultConnectionManager();
        NacosConfig config = NacosConfigLoader.load("nacos-test.properties");
        logger.info("Loaded Nacos config: serverAddr={}, namespace={}, group={}",
                config.getServerAddr(),
                config.getNamespace(),
                config.getGroup());

        ServiceRegistry registry;
        try {
            registry = new NacosServiceRegistry(config);
            logger.info("Created NacosServiceRegistry successfully");
        } catch (NacosException e) {
            logger.error("Failed to create NacosServiceRegistry", e);
            throw new RuntimeException(e);
        }
        return new GatewayServer(PORT, registry, connectionManager);
    }
}
