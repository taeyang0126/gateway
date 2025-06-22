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
package com.lei.java.gateway.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <p>
 * GatewayMessageTest
 * </p>
 *
 * @author 伍磊
 */
public class GatewayMessageTests {

    @Test
    public void testEncodeAndDecodeWithBasicFields() {
        // 创建测试消息
        GatewayMessage message = new GatewayMessage();
        message.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH);
        message.setRequestId(12345L);

        // 编码
        ByteBuf buf = Unpooled.buffer();
        message.encode(buf);

        // 解码
        GatewayMessage decoded = GatewayMessage.decode(buf);

        // 验证基本字段
        assertThat(decoded.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_AUTH);
        assertThat(decoded.getRequestId()).isEqualTo(12345L);
        assertThat(decoded.getClientId()).isNull();
        assertThat(decoded.getBizType()).isNull();
        assertThat(decoded.getExtensions()).isEmpty();
        assertThat(decoded.getBody()).isNull();

        buf.release();
    }

    @Test
    public void testEncodeAndDecodeWithAllFields() {
        // 创建测试消息
        GatewayMessage message = new GatewayMessage();
        message.setMsgType(GatewayMessage.MESSAGE_TYPE_BIZ);
        message.setRequestId(12345L);
        message.setClientId("testClient");
        message.setBizType("testBizType");
        message.getExtensions()
                .put("key1", "value1");
        message.getExtensions()
                .put("key2", "value2");
        message.setBody("Hello, World!".getBytes());

        // 编码
        ByteBuf buf = Unpooled.buffer();
        message.encode(buf);

        // 解码
        GatewayMessage decoded = GatewayMessage.decode(buf);

        // 验证所有字段
        assertThat(decoded.getMsgType()).isEqualTo(GatewayMessage.MESSAGE_TYPE_BIZ);
        assertThat(decoded.getRequestId()).isEqualTo(12345L);
        assertThat(decoded.getClientId()).isEqualTo("testClient");
        assertThat(decoded.getBizType()).isEqualTo("testBizType");
        assertThat(decoded.getExtensions()).hasSize(2)
                .containsEntry("key1", "value1")
                .containsEntry("key2", "value2");
        assertThat(decoded.getBody()).isEqualTo("Hello, World!".getBytes());

        buf.release();
    }

    @Test
    public void testInvalidMagicNumber() {
        // 创建测试消息
        GatewayMessage message = new GatewayMessage();
        message.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH);

        // 编码
        ByteBuf buf = Unpooled.buffer();
        message.encode(buf);

        // 修改魔数
        buf.setShort(8, (short) 0xDEAD);
        // 修改校验和
        int totalLength = buf.getInt(0);
        byte[] data = new byte[totalLength - 4];
        buf.getBytes(8, data);
        buf.setInt(4, GatewayMessage.calculateChecksum(data));

        // 验证解码时抛出异常
        assertThatThrownBy(() -> GatewayMessage.decode(buf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid magic number");

        buf.release();
    }

    @Test
    public void testInsufficientBytes() {
        // 测试消息头不足的情况
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(100); // 只写入长度

        assertThatThrownBy(() -> GatewayMessage.decode(buf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Insufficient bytes for message header");

        // 测试消息体不足的情况
        buf.clear();
        buf.writeInt(1000); // 写入一个大长度
        buf.writeInt(0); // 写入校验和
        buf.writeShort(GatewayMessage.MESSAGE_MAGIC); // 写入魔数
        buf.writeByte(GatewayMessage.MESSAGE_VERSION); // 写入版本

        assertThatThrownBy(() -> GatewayMessage.decode(buf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Insufficient bytes for message body");

        buf.release();
    }

    @Test
    public void testChecksum() {
        // 创建测试消息
        GatewayMessage message = new GatewayMessage();
        message.setMsgType(GatewayMessage.MESSAGE_TYPE_AUTH);
        message.setClientId("testClient");

        // 编码
        ByteBuf buf = Unpooled.buffer();
        message.encode(buf);

        // 修改内容但不更新校验和
        int contentIndex = 8;
        buf.setByte(contentIndex + 5, 0xFF); // 修改某个内容字节

        // 验证解码时抛出异常
        assertThatThrownBy(() -> GatewayMessage.decode(buf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid checksum");

        buf.release();
    }
}
