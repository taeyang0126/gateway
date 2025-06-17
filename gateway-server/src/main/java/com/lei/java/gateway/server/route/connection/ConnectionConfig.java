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

/**
 * 连接配置
 */
public final class ConnectionConfig {

    private static final String ROUTE_SERVICE_CONNECT_TIMEOUT = "ROUTE_SERVICE_CONNECT_TIMEOUT";
    private static final int DEFAULT_CONNECT_TIMEOUT = 3000;

    private static final ConnectionConfig INSTANCE = new ConnectionConfig();

    private final int connectTimeoutMillis;

    private ConnectionConfig() {
        this.connectTimeoutMillis =
                Integer.parseInt(System.getProperty(ROUTE_SERVICE_CONNECT_TIMEOUT,
                        String.valueOf(DEFAULT_CONNECT_TIMEOUT)));
    }

    public static ConnectionConfig getInstance() {
        return INSTANCE;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }
}