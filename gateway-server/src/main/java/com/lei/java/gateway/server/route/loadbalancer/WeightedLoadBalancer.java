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
package com.lei.java.gateway.server.route.loadbalancer;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import com.lei.java.gateway.server.route.ServiceInstance;

/**
 * 加权负载均衡器实现
 */
public class WeightedLoadBalancer implements LoadBalancer {
    private final Random random = new Random();

    @Override
    public ServiceInstance select(List<ServiceInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        // 过滤出健康的实例
        List<ServiceInstance> healthyInstances = instances.stream()
                .filter(ServiceInstance::isHealthy)
                .filter(ServiceInstance::isEnabled)
                .collect(Collectors.toList());

        if (healthyInstances.isEmpty()) {
            return null;
        }

        // 计算总权重
        double totalWeight = healthyInstances.stream()
                .mapToDouble(ServiceInstance::getWeight)
                .sum();

        // 在0到总权重之间随机选择一个值)
        int randomWeight = random.nextInt((int) totalWeight);

        // 根据权重选择实例
        double currentWeight = 0d;
        for (ServiceInstance instance : healthyInstances) {
            currentWeight += instance.getWeight();
            if (randomWeight < currentWeight) {
                return instance;
            }
        }

        // 保底返回最后一个实例
        return healthyInstances.get(healthyInstances.size() - 1);
    }
}