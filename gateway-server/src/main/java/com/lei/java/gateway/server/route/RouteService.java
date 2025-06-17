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

import java.util.concurrent.CompletableFuture;

import com.lei.java.gateway.server.protocol.GatewayMessage;
import com.lei.java.gateway.server.route.connection.ConnectionManager;

/**
 * <p>
 * 路由服务
 * </p>
 *
 * @author 伍磊
 */
public interface RouteService {

    /**
     * 路由并发送消息
     *
     * @param message 网关消息
     * @return 响应消息的Future
     */
    CompletableFuture<GatewayMessage> route(GatewayMessage message);

    /**
     * 获取服务注册中心
     *
     * @return 服务注册中心
     */
    ServiceRegistry getServiceRegistry();

    /**
     * 获取连接管理器
     *
     * @return 连接管理器
     */
    ConnectionManager getConnectionManager();
}
