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
package com.lei.java.gateway.sdk.core.route;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.config.nacos.NacosConfig;
import com.lei.java.gateway.common.route.ServiceInstance;

import static com.lei.java.gateway.common.constants.CacheConstant.GATEWAY_NODE_KEY;
import static com.lei.java.gateway.common.constants.CacheConstant.SESSION_KEY;
import static com.lei.java.gateway.common.constants.GatewayConstant.NODE;
import static com.lei.java.gateway.common.constants.GatewayConstant.SERVER_NAME;

/**
 * <p>
 * GenericClientGatewayLocator
 * </p>
 *
 * @author 伍磊
 */
public class GenericClientGatewayLocator implements ClientGatewayLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenericClientGatewayLocator.class);
    private final RedissonClient redissonClient;
    private final NacosConfig nacosConfig;
    private final NamingService namingService;

    public GenericClientGatewayLocator(RedissonClient redissonClient, NacosConfig nacosConfig)
            throws NacosException {
        this.redissonClient = redissonClient;
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
    public Optional<ServiceInstance> findByClientId(String clientId) {
        if (clientId == null || clientId.isEmpty()) {
            return Optional.empty();
        }

        // 1. getSession By clientId
        String cacheKey = String.format(SESSION_KEY, clientId);
        RMap<String, Object> map = redissonClient.getMap(cacheKey);
        if (!map.isExists()) {
            return Optional.empty();
        }

        // 2. get gateway node
        String gatewayNode = (String) map.get(NODE);

        // 3. check gateway health
        boolean gatewayNodeExists =
                redissonClient.getBucket(String.format(GATEWAY_NODE_KEY, gatewayNode))
                        .isExists();
        if (!gatewayNodeExists) {
            LOGGER.warn("gateway node[{}] does not exist", gatewayNode);
            return Optional.empty();
        }

        List<ServiceInstance> list;
        try {
            // 4. get node address from nacos
            List<Instance> instances =
                    namingService.getAllInstances(SERVER_NAME, nacosConfig.getGroup());
            if (instances == null || instances.isEmpty()) {
                LOGGER.warn("gateway has no instance");
                return Optional.empty();
            }
            list = instances.stream()
                    .map(this::convertFromNacosInstance)
                    .toList();
        } catch (NacosException e) {
            LOGGER.error("Failed to get gateway services");
            return Optional.empty();
        }

        // 5. filter
        return list.stream()
                .filter(ServiceInstance::isHealthy)
                .filter(ServiceInstance::isEnabled)
                // filter node by metadata
                .filter(t -> {
                    Map<String, String> metadata = t.getMetadata();
                    if (null != metadata && !metadata.isEmpty()) {
                        String nodeId = metadata.get(NODE);
                        return gatewayNode.equalsIgnoreCase(nodeId);
                    }
                    return false;
                })
                .findAny();
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
