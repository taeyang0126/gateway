package com.example.gateway.core.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 文件上传与大文件下载集成测试。
 *
 * <p>验证单文件上传、多文件上传、文件+表单字段混合上传流式转发，
 * 以及大文件下载响应流式转发。
 */
class FileUploadDownloadIntegrationTest extends IntegrationTestBase {

    private static final String BOUNDARY = "----TestBoundary"
            + UUID.randomUUID().toString().replace("-", "");

    @Test
    void singleFileUploadForwarded() throws Exception {
        byte[] fileContent = "file-content-hello".getBytes(
                StandardCharsets.UTF_8);
        String body = multipartBody(BOUNDARY,
                "file", "test.txt", "text/plain", fileContent);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/upload"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type",
                        "multipart/form-data; boundary=" + BOUNDARY)
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        // upstream 收到完整的 multipart 请求体
        var received = upstreamServer.getReceivedRequests()
                .get(0);
        String receivedBody = new String(received.body(),
                StandardCharsets.UTF_8);
        assertThat(receivedBody).contains("file-content-hello");
        assertThat(receivedBody).contains("test.txt");
    }

    @Test
    void multiFileUploadForwarded() throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"files\";"
                + " filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "content-a\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"files\";"
                + " filename=\"b.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "content-b\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/upload/multi"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type",
                        "multipart/form-data; boundary=" + BOUNDARY)
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var received = upstreamServer.getReceivedRequests()
                .get(0);
        String receivedBody = new String(received.body(),
                StandardCharsets.UTF_8);
        assertThat(receivedBody).contains("content-a");
        assertThat(receivedBody).contains("content-b");
    }

    @Test
    void fileWithFormFieldsForwarded() throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data;"
                + " name=\"file\"; filename=\"doc.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "doc-content\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data;"
                + " name=\"name\"\r\n\r\n"
                + "test-name\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data;"
                + " name=\"description\"\r\n\r\n"
                + "test-desc\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri(
                        "/api/example/upload/with-fields"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type",
                        "multipart/form-data; boundary=" + BOUNDARY)
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        var received = upstreamServer.getReceivedRequests()
                .get(0);
        String receivedBody = new String(received.body(),
                StandardCharsets.UTF_8);
        assertThat(receivedBody).contains("doc-content");
        assertThat(receivedBody).contains("test-name");
        assertThat(receivedBody).contains("test-desc");
    }

    @Test
    void largeResponseStreamedBack() throws Exception {
        // upstream 返回 100KB 响应
        byte[] largeBody = new byte[100 * 1024];
        for (int i = 0; i < largeBody.length; i++) {
            largeBody[i] = (byte) (i % 256);
        }
        upstreamServer.setHandler(req -> {
            io.netty.buffer.ByteBuf content =
                    io.netty.buffer.Unpooled.wrappedBuffer(
                            largeBody);
            var resp = new io.netty.handler.codec.http
                    .DefaultFullHttpResponse(
                    io.netty.handler.codec.http.HttpVersion
                            .HTTP_1_1,
                    io.netty.handler.codec.http.HttpResponseStatus
                            .OK,
                    content);
            resp.headers().set(
                    io.netty.handler.codec.http.HttpHeaderNames
                            .CONTENT_TYPE,
                    "application/octet-stream");
            resp.headers().setInt(
                    io.netty.handler.codec.http.HttpHeaderNames
                            .CONTENT_LENGTH,
                    content.readableBytes());
            return resp;
        });

        HttpRequest request = HttpRequest.newBuilder()
                .uri(gatewayUri("/api/example/download"))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(100 * 1024);
        assertThat(response.body()).isEqualTo(largeBody);
    }

    private static String multipartBody(String boundary,
            String fieldName, String fileName,
            String contentType, byte[] content) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\""
                + fieldName + "\"; filename=\""
                + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n"
                + new String(content, StandardCharsets.UTF_8)
                + "\r\n"
                + "--" + boundary + "--\r\n";
    }
}
