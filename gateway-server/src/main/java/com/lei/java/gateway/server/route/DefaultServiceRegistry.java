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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.server.route.connection.ConnectionManager;

/**
 * 默认服务注册中心实现
 */
public class DefaultServiceRegistry implements ServiceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(DefaultServiceRegistry.class);

    private final ConcurrentMap<String, List<ServiceInstance>> serviceMap =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthCheckExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConnectionManager connectionManager;

    public DefaultServiceRegistry(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "service-registry-health-check");
            t.setDaemon(true);
            return t;
        });
        startHealthCheck();
    }

    @Override
    public void registerService(String bizType, ServiceInstance instance) {
        if (closed.get()) {
            throw new IllegalStateException("ServiceRegistry is closed");
        }
        if (bizType == null || bizType.isEmpty()) {
            throw new IllegalArgumentException("bizType cannot be empty");
        }
        if (instance == null) {
            throw new IllegalArgumentException("instance cannot be null");
        }

        serviceMap.compute(bizType, (key, existingInstances) -> {
            if (existingInstances == null) {
                existingInstances = new CopyOnWriteArrayList<>();
            }
            if (!existingInstances.contains(instance)) {
                existingInstances.add(instance);
                // 添加了之后就去连接这个节点
                connectionManager.getConnection(instance);
                logger.info("Registered service instance [{}] for bizType [{}]", instance, bizType);
            }
            return existingInstances;
        });
    }

    @Override
    public void removeService(String bizType, ServiceInstance instance) {
        if (bizType == null || bizType.isEmpty()) {
            throw new IllegalArgumentException("bizType cannot be empty");
        }
        if (instance == null) {
            throw new IllegalArgumentException("instance cannot be null");
        }

        serviceMap.computeIfPresent(bizType, (key, instances) -> {
            instances.remove(instance);
            logger.info("Removed service instance [{}] for bizType [{}]", instance, bizType);
            return instances.isEmpty()
                    ? null
                    : instances;
        });
    }

    @Override
    public List<ServiceInstance> getServices(String bizType) {
        if (bizType == null || bizType.isEmpty()) {
            throw new IllegalArgumentException("bizType cannot be empty");
        }
        return serviceMap.getOrDefault(bizType, new ArrayList<>());
    }

    @Override
    public Map<String, List<ServiceInstance>> getAllServices() {
        return new ConcurrentHashMap<>(serviceMap);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            healthCheckExecutor.shutdownNow();
            serviceMap.clear();
            logger.info("ServiceRegistry closed");
        }
    }

    private void startHealthCheck() {
        healthCheckExecutor
                .scheduleWithFixedDelay(this::checkServiceHealth, 30, 30, TimeUnit.SECONDS);
    }

    private void checkServiceHealth() {
        try {
            for (Map.Entry<String, List<ServiceInstance>> entry : serviceMap.entrySet()) {
                String bizType = entry.getKey();
                List<ServiceInstance> instances = entry.getValue();

                for (ServiceInstance instance : instances) {
                    // TODO: 实现实际的健康检查逻辑
                    // 这里可以通过连接测试、心跳等机制来判断服务实例是否健康
                    if (!instance.isHealthy()) {
                        removeService(bizType, instance);
                        logger.warn("Removed unhealthy service instance [{}] for bizType [{}]",
                                instance,
                                bizType);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error during health check", e);
        }
    }
}