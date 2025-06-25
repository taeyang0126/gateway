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

import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

/**
 * 网关消息协议定义。
 * <p>
 * 所有多字节字段均采用 <b>大端序 (Big-Endian)</b> 字节序。 协议各字段定义如下：
 * <ul>
 * <li><b>totalLength</b> (int32, 4字节): 消息总长度，从 `checksum` 字段开始计算，不包含 `totalLength` 自身。
 * <li><b>checksum</b> (int32, 4字节): CRC32 校验和。计算范围为从 `magic` 字段到消息体 `body` 结尾的所有字节。
 * <li><b>magic</b> (int16, 2字节): 魔数，固定值为 <code>0xCAFE</code>。
 * <li><b>version</b> (int8, 1字节): 协议版本，当前固定为 <code>0x01</code>。
 * <li><b>msgType</b> (int8, 1字节): 消息类型，定义如下：
 * <ul>
 * <li><code>0x01</code> - 认证请求</li>
 * <li><code>0x02</code> - 认证响应</li>
 * <li><code>0x03</code> - 心跳</li>
 * <li><code>0x04</code> - 业务消息</li>
 * <li><code>0x05</code> - 推送消息</li>
 * <li><code>0xFF</code> - 错误消息</li>
 * </ul>
 * <li><b>requestId</b> (int64, 8字节): 请求ID，用于关联请求和响应。
 * <li><b>clientId</b> (String, 变长): 客户端ID。结构为：
 * <ul>
 * <li><code>length</code> (int16, 2字节): 字符串内容的字节长度。</li>
 * <li><code>content</code> (UTF-8): 字符串内容。</li>
 * </ul>
 * <li><b>bizType</b> (String, 变长): 业务类型标识。结构同 <code>clientId</code>。
 * <li><b>extensions</b> (Map, 变长): 扩展字段，用于传输额外的元数据。结构为：
 * <ul>
 * <li><code>length</code> (int16, 2字节): 整个扩展字段的总字节长度。</li>
 * <li><code>count</code> (int16, 2字节): Map中条目(entry)的数量。</li>
 * <li><code>entries</code>: 具体的键值对列表，每个条目结构如下：
 * <ul>
 * <li><code>key_len</code> (int16, 2字节)</li>
 * <li><code>key</code> (UTF-8)</li>
 * <li><code>value_len</code> (int16, 2字节)</li>
 * <li><code>value</code> (UTF-8)</li>
 * </ul>
 * </li>
 * </ul>
 * <li><b>body</b> (byte[], 变长): 消息体，存放实际的业务数据。结构为：
 * <ul>
 * <li><code>length</code> (int32, 4字节): 消息体的字节长度。</li>
 * <li><code>content</code> (byte[]): 消息体内容。</li>
 * </ul>
 * </ul>
 * <b>注意事项:</b>
 * <ol>
 * <li>所有字符串均采用 UTF-8 编码。</li>
 * <li>变长字段（如 String, Map, byte[]）的长度字段为0时，表示该字段内容为空。</li>
 * <li>扩展字段为可选字段，其总长度字段为0时，表示没有扩展字段。</li>
 * </ol>
 */
public class GatewayMessage {
    // 协议常量
    public static final int HEADER_LENGTH = 8; // 总长度(4) + 校验和(4)
    public static final short MESSAGE_MAGIC = (short) 0xCAFE;
    public static final byte MESSAGE_VERSION = 0x01;

    // 消息类型定义
    public static final byte MESSAGE_TYPE_AUTH = (byte) 0x01; // 认证消息
    public static final byte MESSAGE_TYPE_AUTH_SUCCESS_RESP = (byte) 0x02; // 认证成功
    public static final byte MESSAGE_TYPE_AUTH_FAIL_RESP = (byte) 0x03; // 认证失败
    public static final byte MESSAGE_TYPE_HEARTBEAT = (byte) 0x04; // 心跳消息
    public static final byte MESSAGE_TYPE_BIZ = (byte) 0x05; // 业务消息
    public static final byte MESSAGE_TYPE_PUSH = (byte) 0x06; // 推送消息
    public static final byte MESSAGE_TYPE_PUSH_SUCCESS = (byte) 0x07; // 推送成功
    public static final byte MESSAGE_TYPE_PUSH_FAIL = (byte) 0x08; // 推送失败
    public static final byte MESSAGE_TYPE_PUSH_HEARTBEAT = (byte) 0x09; // 推送心跳消息
    public static final byte MESSAGE_TYPE_ERROR = (byte) 0xFF; // 错误消息

    // 消息头
    private short magic = MESSAGE_MAGIC;
    private byte version = MESSAGE_VERSION;
    private byte msgType;

    private long requestId; // 请求ID
    private String clientId; // 客户端标识
    private String bizType; // 业务类型标识

    // 消息体
    private Map<String, String> extensions = new HashMap<>();
    private byte[] body;

    public static GatewayMessage decode(ByteBuf in) {
        // 1. 确保有足够的字节可读
        if (in.readableBytes() < HEADER_LENGTH) {
            throw new IllegalArgumentException("Insufficient bytes for message header");
        }

        // 2. 读取整体长度
        int totalLength = in.readInt();
        if (in.readableBytes() < totalLength - 4) {
            throw new IllegalArgumentException("Insufficient bytes for message body");
        }

        // 3. 验证校验和
        int checksum = in.readInt();
        int readerIndex = in.readerIndex();
        // 减去校验和的长度
        byte[] bytes = new byte[totalLength - 4];
        in.getBytes(readerIndex, bytes);
        if (checksum != calculateChecksum(bytes)) {
            throw new IllegalArgumentException("Invalid checksum");
        }

        // 4. 读取消息内容
        GatewayMessage message = new GatewayMessage();
        message.magic = in.readShort();
        if (message.magic != MESSAGE_MAGIC) {
            throw new IllegalArgumentException("Invalid magic number");
        }

        message.version = in.readByte();
        message.msgType = in.readByte();
        message.requestId = in.readLong();

        // 读取clientId
        short clientIdLength = in.readShort();
        if (clientIdLength > 0) {
            byte[] clientIdBytes = new byte[clientIdLength];
            in.readBytes(clientIdBytes);
            message.clientId = new String(clientIdBytes, CharsetUtil.UTF_8);
        }

        // 读取业务类型
        short bizTypeLength = in.readShort();
        if (bizTypeLength > 0) {
            byte[] bizTypeBytes = new byte[bizTypeLength];
            in.readBytes(bizTypeBytes);
            message.bizType = new String(bizTypeBytes, CharsetUtil.UTF_8);
        }

        // 读取扩展字段
        short extensionsLength = in.readShort();
        if (extensionsLength > 0) {
            short count = in.readShort();
            for (int i = 0; i < count; i++) {
                short keyLen = in.readShort();
                byte[] keyBytes = new byte[keyLen];
                in.readBytes(keyBytes);
                String key = new String(keyBytes, CharsetUtil.UTF_8);

                short valueLen = in.readShort();
                byte[] valueBytes = new byte[valueLen];
                in.readBytes(valueBytes);
                String value = new String(valueBytes, CharsetUtil.UTF_8);

                message.extensions.put(key, value);
            }
        }

        // 读取消息体
        int bodyLength = in.readInt();
        if (bodyLength > 0) {
            message.body = new byte[bodyLength];
            in.readBytes(message.body);
        }

        return message;
    }

    public void encode(ByteBuf out) {
        // 1. 写入长度占位符
        int lengthIndex = out.writerIndex();
        out.writeInt(0);

        // 2. 写入校验和占位符
        int checksumIndex = out.writerIndex();
        out.writeInt(0);

        // 3. 写入消息内容
        int contentStartIndex = out.writerIndex();
        out.writeShort(magic);
        out.writeByte(version);
        out.writeByte(msgType);
        out.writeLong(requestId);

        // 写入clientId
        byte[] clientIdBytes = clientId != null
                ? clientId.getBytes(CharsetUtil.UTF_8)
                : new byte[0];
        out.writeShort(clientIdBytes.length);
        if (clientIdBytes.length > 0) {
            out.writeBytes(clientIdBytes);
        }

        // 写入业务类型
        byte[] bizTypeBytes = bizType != null
                ? bizType.getBytes(CharsetUtil.UTF_8)
                : new byte[0];
        out.writeShort(bizTypeBytes.length);
        if (bizTypeBytes.length > 0) {
            out.writeBytes(bizTypeBytes);
        }

        // 写入扩展字段
        int extensionsBytes = calculateExtensionsLength();
        out.writeShort(extensionsBytes);
        if (extensionsBytes > 0) {
            out.writeShort(extensions.size());
            for (Map.Entry<String, String> entry : extensions.entrySet()) {
                byte[] keyBytes = entry.getKey()
                        .getBytes(CharsetUtil.UTF_8);
                byte[] valueBytes = entry.getValue()
                        .getBytes(CharsetUtil.UTF_8);
                out.writeShort(keyBytes.length);
                out.writeBytes(keyBytes);
                out.writeShort(valueBytes.length);
                out.writeBytes(valueBytes);
            }
        }

        // 写入消息体
        if (body != null) {
            out.writeInt(body.length);
            out.writeBytes(body);
        } else {
            out.writeInt(0);
        }

        // 4. 计算总长度和校验和
        int totalLength = out.writerIndex() - contentStartIndex;
        byte[] messageBytes = new byte[totalLength];
        out.getBytes(contentStartIndex, messageBytes);

        out.setInt(lengthIndex, totalLength + 4);
        out.setInt(checksumIndex, calculateChecksum(messageBytes));
    }

    public static int calculateChecksum(byte[] bytes) {
        CRC32 crc32 = new CRC32();
        crc32.update(bytes, 0, bytes.length);
        return (int) crc32.getValue();
    }

    private int calculateExtensionsLength() {
        if (extensions.isEmpty()) {
            return 0;
        }
        int length = 2; // size of map (short)
        for (Map.Entry<String, String> entry : extensions.entrySet()) {
            length += 2 + entry.getKey()
                    .getBytes(CharsetUtil.UTF_8).length;
            length += 2 + entry.getValue()
                    .getBytes(CharsetUtil.UTF_8).length;
        }
        return length;
    }

    // Getters and setters
    public byte getMsgType() {
        return msgType;
    }

    public void setMsgType(byte msgType) {
        this.msgType = msgType;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public Map<String, String> getExtensions() {
        return extensions;
    }

    public void setExtensions(Map<String, String> extensions) {
        this.extensions = extensions;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    @Override
    public String toString() {
        return "GatewayMessage{"
                + "magic="
                + magic
                + ", version="
                + version
                + ", msgType="
                + msgType
                + ", requestId="
                + requestId
                + ", clientId='"
                + clientId
                + '\''
                + ", bizType='"
                + bizType
                + '\''
                + ", extensions="
                + extensions
                + ", body="
                + (body == null
                        ? ""
                        : new String(body))
                + '}';
    }
}
