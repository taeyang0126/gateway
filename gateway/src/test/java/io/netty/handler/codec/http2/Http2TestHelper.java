package io.netty.handler.codec.http2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试辅助类，放在 Netty 包下以访问 package-private API。
 *
 * <p>提供创建 mock Http2FrameCodec 的能力，其 newStream() 返回指定 id 的 Http2FrameStream。
 */
public final class Http2TestHelper {

    private Http2TestHelper() {
    }

    /**
     * 创建一个 mock 的 Http2FrameCodec，其 newStream() 返回 mock 的 Http2FrameStream。
     *
     * @param streamId newStream() 返回的 frameStream.id() 值
     * @return mock 的 Http2FrameCodec
     */
    public static Http2FrameCodec mockFrameCodec(int streamId) {
        Http2FrameCodec codec = mock(Http2FrameCodec.class);
        Http2FrameCodec.DefaultHttp2FrameStream frameStream =
                mock(Http2FrameCodec.DefaultHttp2FrameStream.class);
        when(frameStream.id()).thenReturn(streamId);
        when(codec.newStream()).thenReturn(frameStream);
        return codec;
    }
}
