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

import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import com.lei.java.gateway.common.codec.GatewayMessageCodec;

import static com.lei.java.gateway.common.constants.GatewayConstant.GATEWAY_READ_IDLE_TIMEOUT_SECONDS;

/**
 * <p>
 * GatewayClientChannelInitializer
 * </p>
 *
 * @author 伍磊
 */
public class GatewayClientChannelInitializer extends ChannelInitializer<NioSocketChannel> {

    @Override
    protected void initChannel(NioSocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new IdleStateHandler(
                0,
                GATEWAY_READ_IDLE_TIMEOUT_SECONDS / 2,
                0,
                TimeUnit.SECONDS));
        // 添加消息编解码器
        p.addLast(new GatewayMessageCodec());
        p.addLast(new GatewayClientHandler());
    }

}
