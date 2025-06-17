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

import com.lei.java.gateway.server.route.ServiceInstance;

/**
 * <p>
 * 连接管理
 * </p>
 *
 * @author 伍磊
 */
public interface ConnectionManager {

    /**
     * 获取或创建到指定服务实例的连接
     *
     * @param instance 服务实例
     * @return 连接的Future
     */
    CompletableFuture<Connection> getConnection(ServiceInstance instance);

    /**
     * 释放连接
     *
     * @param connection 要释放的连接
     */
    void releaseConnection(Connection connection);

    /**
     * 移除并关闭连接
     *
     * @param connection 要关闭的连接
     */
    void removeConnection(Connection connection);

    /**
     * 关闭所有连接并释放资源
     */
    void close();

    /**
     * 获取连接超时时间
     *
     * @return 超时时间（毫秒）
     */
    default int getConnectTimeoutMillis() {
        return ConnectionConfig.getInstance()
                .getConnectTimeoutMillis();
    }

}
