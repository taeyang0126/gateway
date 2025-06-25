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
package com.lei.java.gateway.common.codec;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import com.lei.java.gateway.common.protocol.GatewayMessage;

public class GatewayMessageCodec extends ByteToMessageCodec<GatewayMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, GatewayMessage msg, ByteBuf out)
            throws Exception {
        msg.encode(out);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
            throws Exception {
        // 确保有足够的字节可读
        if (in.readableBytes() < GatewayMessage.HEADER_LENGTH) { // 整体长度(4) + 校验和(4)
            return;
        }

        // 标记当前读取位置
        in.markReaderIndex();

        // 读取消息总长度
        int totalLength = in.readInt();
        if (in.readableBytes() < totalLength) {
            in.resetReaderIndex();
            return;
        }

        // 解码消息
        in.resetReaderIndex();
        GatewayMessage message = GatewayMessage.decode(in);
        out.add(message);
    }
}