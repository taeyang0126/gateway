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
package com.lei.java.gateway.sdk.core.exception;

/**
 * <p>
 * 网关连接异常
 * </p>
 *
 * @author 伍磊
 */
public class GatewayConnectionException extends MessagingException {

    public GatewayConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}