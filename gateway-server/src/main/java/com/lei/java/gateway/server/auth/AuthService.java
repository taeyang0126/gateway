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
package com.lei.java.gateway.server.auth;

import com.lei.java.gateway.common.protocol.GatewayMessage;

/**
 * <p>
 * AuthService
 * </p>
 *
 * @author 伍磊
 */
public interface AuthService {

    /**
     * 认证
     *
     * @param msg 消息
     * @return
     */
    boolean authenticate(GatewayMessage msg);

}
