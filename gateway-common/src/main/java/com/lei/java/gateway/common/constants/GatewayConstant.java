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
package com.lei.java.gateway.common.constants;

/**
 * <p>
 * GatewayConstant
 * </p>
 *
 * @author 伍磊
 */
public interface GatewayConstant {

    String SERVER_NAME = "gateway";

    String HOST = "host";

    String PORT = "port";

    String NODE = "node";

    String TIMESTAMP = "timestamp";

    String SESSION_ID = "sessionId";

    String LAST_ACTIVE_TIME = "lastActiveTime";

    int GATEWAY_HEARTBEAT_INTERVAL_SECONDS = 30;
    int GATEWAY_HEARTBEAT_TIMEOUT_SECOND = 45;
    int GATEWAY_READ_IDLE_TIMEOUT_SECONDS = 60;
    int SESSION_EXPIRE_SECONDS = 75;

    // 最大重连尝试次数
    int MAX_RECONNECT_ATTEMPTS = 5;
    int CONNECT_TIMEOUT_MILLIS = 5000;
}
