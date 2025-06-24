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
package com.lei.java.gateway.server.client;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import eu.rekawek.toxiproxy.Proxy;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Timer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lei.java.gateway.common.client.AbstractClient;
import com.lei.java.gateway.common.client.ClientManager;
import com.lei.java.gateway.common.client.GenericClientManager;
import com.lei.java.gateway.common.route.ServiceInstance;
import com.lei.java.gateway.server.base.BaseIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <p>
 * {@link AbstractClient} 测试
 * </p>
 *
 * @author 伍磊
 */
public class NettyClientIT extends BaseIntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyClientIT.class);
    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    // 这里端口有限制 -> TOXIPROXY_CONTAINER.getExposedPorts()
    private final int port = 8680;
    private static final BlockingQueue<String> BLOCKING_QUEUE = new LinkedBlockingQueue<>();

    @Test
    public void test() throws IOException, InterruptedException {
        GenericClientManager.ClientFactory<ClientExample> clientFactory = ClientExample::new;
        ClientManager<ClientExample> clientManager = new GenericClientManager<>(clientFactory);

        // 代理 httpBin
        Proxy proxy = toxiproxyClient.createProxy("NettyClientIT",
                "0.0.0.0:"
                        + port,
                HTTPBIN
                        + ":"
                        + HTTPBIN_PORT);

        ServiceInstance serviceInstance = new ServiceInstance(
                TOXIPROXY_CONTAINER.getHost(),
                TOXIPROXY_CONTAINER.getMappedPort(port));
        ClientExample clientExample = clientManager.getClient(serviceInstance)
                .join();
        assertThat(clientExample).isNotNull()
                .matches(AbstractClient::isActive);
        assertThat(BLOCKING_QUEUE.poll(1, TimeUnit.SECONDS)).isNotNull()
                .isEqualTo(ACTIVE);

        // 不启用代理，但是底层的 tcp 连接还是可以用的
        // 所以 client 会出现连接成功又马上断开的情况，这是因为底层的 tcp 连接成功了
        // 但是 proxy disable，所以连接马上又断开了
        proxy.disable();
        LOGGER.info("proxy disabled");
        // 断开连接接收到的一定是 INACTIVE
        assertThat(BLOCKING_QUEUE.poll(5, TimeUnit.SECONDS)).isNotNull()
                .isEqualTo(INACTIVE);
        assertThat(clientExample).isNotNull()
                .matches(t -> !t.isActive());

        // 重新启用
        proxy.enable();
        LOGGER.info("proxy enabled");
        assertThat(BLOCKING_QUEUE.poll(5, TimeUnit.SECONDS)).isNotNull()
                .isEqualTo(ACTIVE);
        assertThat(clientExample).isNotNull()
                .matches(AbstractClient::isActive);

        clientManager.close();
        BLOCKING_QUEUE.clear();
    }

    public static class ClientExample extends AbstractClient<ClientExample> {
        public ClientExample(ServiceInstance instance, EventLoopGroup group, Timer timer) {
            super(instance, group, timer);
        }

        @Override
        protected void initBusinessHandlers(ChannelPipeline pipeline) {
            pipeline.addLast(new EventQueueTestHandler(BLOCKING_QUEUE));
        }
    }

    public static class EventQueueTestHandler extends ChannelInboundHandlerAdapter {

        // 这个队列将在测试主线程和Netty IO线程之间共享
        private final BlockingQueue<String> eventQueue;

        public EventQueueTestHandler(BlockingQueue<String> eventQueue) {
            this.eventQueue = eventQueue;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // 发生连接激活事件，往队列里放一个 "ACTIVE" 标记
            eventQueue.offer(ACTIVE);
            ctx.fireChannelActive();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // 发生连接断开事件，往队列里放一个 "INACTIVE" 标记
            eventQueue.offer(INACTIVE);
            ctx.fireChannelInactive();
        }
    }
}
