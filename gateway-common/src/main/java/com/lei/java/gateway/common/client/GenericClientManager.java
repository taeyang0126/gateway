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
package com.lei.java.gateway.common.client;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.route.ServiceInstance;

/**
 * <p>
 * GenericNettyClientManager
 * </p>
 *
 * @author 伍磊
 */
public class GenericClientManager<T extends Client<T>> implements ClientManager<T> {
    private static final Logger logger = LoggerFactory.getLogger(GenericClientManager.class);

    private final Map<ServiceInstance, T> clients;
    // 一个静态的、线程安全的 Map，用于缓存正在进行的连接尝试。
    private final Map<ServiceInstance, CompletableFuture<T>> pendingClients;
    private final EventLoopGroup workerGroup;
    private final Timer timer;
    private final ClientFactory<T> clientFactory;

    public GenericClientManager(ClientFactory<T> clientFactory) {
        this(clientFactory,
                new MultiThreadIoEventLoopGroup(
                        Runtime.getRuntime()
                                .availableProcessors(),
                        NioIoHandler.newFactory()));
    }

    public GenericClientManager(ClientFactory<T> clientFactory, EventLoopGroup workerGroup) {
        this.clients = new ConcurrentHashMap<>();
        this.pendingClients = new ConcurrentHashMap<>();
        this.workerGroup = workerGroup;
        this.timer = new HashedWheelTimer();
        this.clientFactory = clientFactory;
    }

    @Override
    public CompletableFuture<T> getClient(ServiceInstance instance) {
        T client = clients.get(instance);
        if (client != null) {
            return CompletableFuture.completedFuture(client);
        }
        return pendingClients.computeIfAbsent(instance, key -> {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.whenComplete((completableClient, throwable) -> {
                if (throwable != null) {
                    logger.error("Failed to connect to instance: {}", key, throwable);
                }
                if (completableClient != null) {
                    clients.put(key, completableClient);
                }
                // 无论成功、失败还是超时，都从 pending 列表中移除
                pendingClients.remove(key);
            });
            T newClient = clientFactory.newClient(key, this.workerGroup, this.timer);
            newClient.connect(future);
            return future;
        });
    }

    @Override
    public void removeClient(T client) {
        if (client != null) {
            clients.remove(client.getInstance());
            client.shutdown();
        }
    }

    @Override
    public void close() {
        clients.values()
                .forEach(T::shutdown);
        clients.clear();
        timer.stop();
        workerGroup.shutdownGracefully();
        logger.info("NettyClientManager has been shut down.");
    }

    @FunctionalInterface
    public interface ClientFactory<T> {
        T newClient(ServiceInstance instance, EventLoopGroup group, Timer timer);
    }
}
