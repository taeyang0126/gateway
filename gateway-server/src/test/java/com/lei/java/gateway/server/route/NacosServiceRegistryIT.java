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
package com.lei.java.gateway.server.route;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.base.BaseIntegrationTest;
import com.lei.java.gateway.server.config.nacos.NacosConfig;
import com.lei.java.gateway.server.config.nacos.NacosConfigLoader;
import com.lei.java.gateway.server.route.nacos.NacosServiceRegistry;

import static org.assertj.core.api.Assertions.assertThat;

public class NacosServiceRegistryIT extends BaseIntegrationTest {

    private static String testServiceName;
    private static ServiceRegistry serviceRegistry;
    private static ServiceInstance testInstance;

    @BeforeAll
    public static void init() throws NacosException {
        NacosConfig nacosConfig = NacosConfigLoader.load("nacos-test.properties");
        serviceRegistry = new NacosServiceRegistry(nacosConfig);
        testServiceName = "nacos-service-registry-test"
                + System.nanoTime();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("version", "1.0.0");
        metadata.put("environment", "test");
        testInstance = new ServiceInstance("127.0.0.1", 8080, metadata);
    }

    @Test
    public void test() throws Exception {
        serviceRegistry.registerService(testServiceName, testInstance);
        List<ServiceInstance> services = serviceRegistry.getServices(testServiceName);
        assertThat(services).isNotNull();
        assertThat(services).isNotEmpty();
        ServiceInstance registeredInstance = services.getFirst();
        assertThat(registeredInstance.getHost()).isEqualTo(testInstance.getHost());
        assertThat(registeredInstance.getPort()).isEqualTo(testInstance.getPort());

        serviceRegistry.removeService(testServiceName, testInstance);
        TimeUnit.SECONDS.sleep(1);
        List<ServiceInstance> servicesAfterRemoval = serviceRegistry.getServices(testServiceName);
        assertThat(servicesAfterRemoval.isEmpty()).isTrue();
    }

}
