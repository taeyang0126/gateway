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
package com.lei.java.gateway.server.route.connection;

import java.util.concurrent.CompletableFuture;

import io.netty.channel.Channel;

import com.lei.java.gateway.common.protocol.GatewayMessage;
import com.lei.java.gateway.server.route.ServiceInstance;

/**
 * <p>
 * 与上游服务的连接
 * </p>
 *
 * @author 伍磊
 */
public interface Connection {

    /**
     * 获取底层Channel
     */
    Channel getChannel();

    /**
     * 获取连接的服务实例
     */
    ServiceInstance getServiceInstance();

    /**
     * 发送消息
     *
     * @param message 网关消息
     * @return 响应的Future
     */
    CompletableFuture<GatewayMessage> send(GatewayMessage message);

    /**
     * 连接是否活跃
     */
    boolean isActive();

    /**
     * 关闭连接
     */
    void close();

    /**
     * 处理响应消息
     *
     * @param message message
     */
    void handleResponse(GatewayMessage message);

}
