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

import com.lei.java.gateway.server.route.ServiceInstance;

/**
 * 负载均衡器接口
 */
public interface LoadBalancer {
    /**
     * 从服务实例列表中选择一个实例
     *
     * @param instances 可用的服务实例列表
     * @return 选中的服务实例，如果列表为空则返回null
     */
    ServiceInstance select(List<ServiceInstance> instances);
}