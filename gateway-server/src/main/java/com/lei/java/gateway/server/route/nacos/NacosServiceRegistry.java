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
package com.lei.java.gateway.server.route.nacos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.config.nacos.NacosConfig;
import com.lei.java.gateway.common.constants.GatewayConstant;
import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.server.route.ServiceRegistry;

public class NacosServiceRegistry implements ServiceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(NacosServiceRegistry.class);

    private final NamingService namingService;
    private final NacosConfig nacosConfig;

    public NacosServiceRegistry(NacosConfig nacosConfig) throws NacosException {
        this.nacosConfig = nacosConfig;
        this.namingService = NamingFactory.createNamingService(nacosConfig.buildProperties());

        // 检查服务连接状态
        String status = namingService.getServerStatus();
        if (!"UP".equals(status)) {
            throw new NacosException(
                    500,
                    "Nacos server is not ready, status: "
                            + status);
        }
    }

    @Override
    public void registerService(String bizType, ServiceInstance instance) {
        try {
            Instance nacosInstance = convertToNacosInstance(instance);
            // 注册服务实例
            logger.info("Registering service: {} with instance: {}", bizType, nacosInstance);
            namingService.registerInstance(bizType, nacosConfig.getGroup(), nacosInstance);
        } catch (NacosException e) {
            logger.error("Failed to register service: "
                    + bizType, e);
            throw new RuntimeException("Failed to register service", e);
        }
    }

    @Override
    public void removeService(String bizType, ServiceInstance instance) {
        try {
            Instance nacosInstance = convertToNacosInstance(instance);
            namingService.deregisterInstance(bizType, nacosConfig.getGroup(), nacosInstance);
            logger.info("Successfully deregistered service: {} with instance: {}",
                    bizType,
                    instance);
        } catch (NacosException e) {
            logger.error("Failed to deregister service: "
                    + bizType, e);
            throw new RuntimeException("Failed to deregister service", e);
        }
    }

    @Override
    public List<ServiceInstance> getServices(String bizType) {
        try {
            List<Instance> instances =
                    namingService.getAllInstances(bizType, nacosConfig.getGroup());
            logger.debug("Found {} instances for bizType={}", instances.size(), bizType);
            return instances.stream()
                    .map(this::convertFromNacosInstance)
                    .collect(Collectors.toList());
        } catch (NacosException e) {
            logger.error("Failed to get services for bizType: "
                    + bizType, e);
            throw new RuntimeException("Failed to get services", e);
        }
    }

    @Override
    public Map<String, List<ServiceInstance>> getAllServices() {
        try {
            Map<String, List<ServiceInstance>> result = new HashMap<>();
            List<String> services =
                    namingService.getServicesOfServer(1, Integer.MAX_VALUE, nacosConfig.getGroup())
                            .getData();

            for (String service : services) {
                result.put(service, getServices(service));
            }
            return result;
        } catch (NacosException e) {
            logger.error("Failed to get all services", e);
            throw new RuntimeException("Failed to get all services", e);
        }
    }

    @Override
    public void close() {
        try {
            namingService.shutDown();
            logger.info("Successfully shut down Nacos naming service");
        } catch (NacosException e) {
            logger.error("Error shutting down Nacos naming service", e);
        }
    }

    private Instance convertToNacosInstance(ServiceInstance serviceInstance) {
        Instance instance = new Instance();
        instance.setIp(serviceInstance.getHost());
        instance.setPort(serviceInstance.getPort());
        instance.setWeight(serviceInstance.getWeight());
        instance.setHealthy(serviceInstance.isHealthy());
        instance.setEnabled(serviceInstance.isEnabled());

        // 设置元数据
        Map<String, String> metadata = new HashMap<>(serviceInstance.getMetadata());
        // 添加一些默认元数据
        metadata.putIfAbsent(GatewayConstant.TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        instance.setMetadata(metadata);

        return instance;
    }

    private ServiceInstance convertFromNacosInstance(Instance nacosInstance) {
        return new ServiceInstance(
                nacosInstance.getIp(),
                nacosInstance.getPort(),
                nacosInstance.getWeight(),
                nacosInstance.getMetadata(),
                nacosInstance.isHealthy(),
                nacosInstance.isEnabled());
    }
}