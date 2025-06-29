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
import com.lei.java.gateway.server.domain.AuthResult;

import static com.lei.java.gateway.common.config.security.SecurityConfig.INNER_TOKEN_VALUE;
import static com.lei.java.gateway.common.config.security.SecurityConfig.TOKEN_NAME;
import static com.lei.java.gateway.common.config.security.SecurityConfig.TOKEN_VALUE;

/**
 * <p>
 * AuthService
 * </p>
 *
 * @author 伍磊
 */
public class DefaultAuthService implements AuthService {

    @Override
    public AuthResult authenticate(GatewayMessage msg) {
        byte msgType = msg.getMsgType();
        if (GatewayMessage.MESSAGE_TYPE_AUTH != msgType) {
            return new AuthResult(false, false);
        }
        // TODO 固定从 extensions 中拿到 token
        String token = msg.getExtensions()
                .get(TOKEN_NAME);
        if (token == null) {
            return new AuthResult(false, false);
        }
        if (TOKEN_VALUE.equals(token)) {
            return new AuthResult(true, true);
        }
        if (INNER_TOKEN_VALUE.equals(token)) {
            return new AuthResult(true, false);
        }
        return new AuthResult(false, false);
    }
}
