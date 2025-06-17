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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.lei.java.gateway.server.route.ServiceInstance;

/**
 * 轮询负载均衡器实现
 */
public class RoundRobinLoadBalancer implements LoadBalancer {
    private final AtomicInteger counter = new AtomicInteger(0);

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

        // 轮询选择一个实例
        int index = Math.abs(counter.getAndIncrement() % healthyInstances.size());
        return healthyInstances.get(index);
    }
}