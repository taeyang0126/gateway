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
package com.lei.java.gateway.server.codec;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import com.lei.java.gateway.server.protocol.GatewayMessage;

import static org.assertj.core.api.Assertions.assertThat;

public class GatewayMessageCodecTests {

    @Test
    void testEncodeAndDecode() {
        // 1. 创建一个带有自定义编解码器的 EmbeddedChannel
        EmbeddedChannel channel = new EmbeddedChannel(new GatewayMessageCodec());

        // 2. 准备要发送的消息
        GatewayMessage message = new GatewayMessage();
        message.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        message.setRequestId(System.currentTimeMillis());
        message.setClientId(UUID.randomUUID()
                .toString());
        message.setBizType("testBizType");
        message.getExtensions()
                .put("key", "value");
        message.setBody("Hello".getBytes());

        // 3. (编码) 将消息写入出站缓冲区
        assertThat(channel.writeOutbound(message)).isTrue();

        // 3.5. 刷出缓冲区，使数据可读
        // 注意：如果您的编码器内部调用的是 ctx.writeAndFlush()，则无需手动调用此行。
        // 但在测试中显式调用 flush 是更稳妥、更清晰的做法。
        channel.flushOutbound();

        // 4. 从出站缓冲区读取编码后的数据
        ByteBuf encoded = channel.readOutbound();
        assertThat(encoded).isNotNull();

        // 5. (解码) 将编码后的数据写入入站缓冲区
        assertThat(channel.writeInbound(encoded)).isTrue();

        // 6. (验证) 从入站缓冲区读取解码后的消息
        GatewayMessage decodedMessage = channel.readInbound();
        assertThat(decodedMessage).isNotNull();

        // 验证解码后的消息内容是否与原始消息一致
        assertThat(decodedMessage.getMsgType()).isEqualTo(message.getMsgType());
        assertThat(decodedMessage.getRequestId()).isEqualTo(message.getRequestId());
        assertThat(decodedMessage.getClientId()).isEqualTo(message.getClientId());
        assertThat(decodedMessage.getBody()).isEqualTo(message.getBody());
        assertThat(decodedMessage.getExtensions()
                .get("key")).isEqualTo("value");

        // 7. 所有操作完成后，最后调用 finish() 来关闭通道并清理资源
        assertThat(channel.finish()).isFalse();

        // 8. 确认通道中没有剩余的入站/出站数据
        assertThat((Object) channel.readInbound()).isNull();
        assertThat((Object) channel.readOutbound()).isNull();
    }

}