package io.netty.handler.codec.http2;

/**
 * 桥接工具类，暴露 {@link Http2FrameCodec} 的 package-private {@code newStream()} 方法。
 *
 * <p>放在 {@code io.netty.handler.codec.http2} 包下以访问 package-private API。
 * 这是 Netty 生态中的常见做法（gRPC、Armeria 等框架均采用类似方式）。
 *
 * <p>用途：ProxyHandler 在写 HEADERS 帧前需要创建 {@link Http2FrameStream} 对象，
 * 以便通过高层帧 API 发起新 stream，同时保留自增 stream ID 的控制权。
 */
public final class Http2FrameCodecAccess {

    private Http2FrameCodecAccess() {
    }

    /**
     * 创建一个新的 {@link Http2FrameStream}。
     *
     * <p>返回的 stream 尚未分配 ID，需设置到 {@link DefaultHttp2HeadersFrame} 上，
     * 通过 {@code ctx.write(headersFrame)} 写入后由 {@link Http2FrameCodec} 自动分配 ID。
     *
     * @param codec Http2FrameCodec 实例（从 pipeline 获取）
     * @return 新的 Http2FrameStream
     */
    public static Http2FrameStream newStream(Http2FrameCodec codec) {
        return codec.newStream();
    }
}
