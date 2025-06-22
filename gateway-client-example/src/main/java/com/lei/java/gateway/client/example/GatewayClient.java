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
package com.lei.java.gateway.client.example;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * GatewayClient
 * </p>
 *
 * @author 伍磊
 */
public class GatewayClient {
    private static final Logger logger = LoggerFactory.getLogger(GatewayClient.class);
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8888;
    private static final EventLoopGroup WORK_GROUP =
            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

    public static void main(String[] args) {
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(WORK_GROUP)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new GatewayClientChannelInitializer());
            ChannelFuture channelFuture = bootstrap.connect(HOST, PORT)
                    .syncUninterruptibly();
            logger.info("connect success: {}",
                    channelFuture.channel()
                            .localAddress());
            channelFuture.channel()
                    .closeFuture()
                    .syncUninterruptibly();
        } finally {
            WORK_GROUP.shutdownGracefully();
        }
    }

}
