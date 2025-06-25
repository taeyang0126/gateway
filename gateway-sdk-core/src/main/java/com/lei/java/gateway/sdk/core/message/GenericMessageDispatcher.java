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
package com.lei.java.gateway.sdk.core.message;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.lei.java.gateway.common.client.ClientManager;
import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.sdk.core.client.GatewayPushClient;
import com.lei.java.gateway.sdk.core.domain.PushResult;
import com.lei.java.gateway.sdk.core.exception.GatewayConnectionException;
import com.lei.java.gateway.sdk.core.exception.RecipientNotReachableException;
import com.lei.java.gateway.sdk.core.route.ClientGatewayLocator;

/**
 * <p>
 * GenericMessageDispatcher
 * </p>
 *
 * @author 伍磊
 */
public class GenericMessageDispatcher implements MessageDispatcher {
    private final ClientGatewayLocator clientGatewayLocator;
    private final ClientManager<GatewayPushClient> clientManager;

    public GenericMessageDispatcher(
            ClientGatewayLocator clientGatewayLocator,
            ClientManager<GatewayPushClient> clientManager) {
        this.clientGatewayLocator = clientGatewayLocator;
        this.clientManager = clientManager;
    }

    @Override
    public CompletableFuture<PushResult> dispatch(Long requestId, String clientId, byte[] content) {
        CompletableFuture<PushResult> completableFuture = new CompletableFuture<>();
        Optional<ServiceInstance> gatewayNodeOptional =
                clientGatewayLocator.findByClientId(clientId);
        if (gatewayNodeOptional.isEmpty()) {
            completableFuture.completeExceptionally(new RecipientNotReachableException(
                    "Recipient not found or offline for client: "
                            + clientId,
                    clientId));
            return completableFuture;
        }

        ServiceInstance gatewayNode = gatewayNodeOptional.get();
        clientManager.getClient(gatewayNode)
                .whenComplete((client, throwable) -> {
                    if (throwable != null) {
                        completableFuture.completeExceptionally(new GatewayConnectionException(
                                "gateway can not connected",
                                throwable));
                    } else {
                        client.send(requestId, clientId, content, completableFuture);
                    }
                });

        return completableFuture;
    }

    @Override
    public void shutdown() {
        clientManager.shutdown();
    }
}
