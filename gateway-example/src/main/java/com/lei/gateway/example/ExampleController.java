package com.lei.gateway.example;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Mock 上游服务控制器，提供普通 REST 接口、文件上传和大文件下载端点。
 */
@RestController
@RequestMapping("/api/example")
public class ExampleController {

    /**
     * 简单 GET 接口。
     *
     * @return 问候文本
     */
    @GetMapping("/hello")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Hello from upstream!");
    }

    /**
     * POST echo 接口，原样返回请求体。
     *
     * @param body 请求体
     * @return 请求体内容
     */
    @PostMapping("/echo")
    public ResponseEntity<String> echo(@RequestBody String body) {
        return ResponseEntity.ok(body);
    }

    /**
     * 单文件上传接口。
     *
     * @param file 上传的文件
     * @return 文件名和大小
     */
    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok("Uploaded: " + file.getOriginalFilename()
                + ", size: " + file.getSize());
    }

    /**
     * 多文件上传接口。
     *
     * @param files 上传的文件列表
     * @return 文件数量和各文件信息
     */
    @PostMapping("/upload/multi")
    public ResponseEntity<String> uploadMulti(
            @RequestParam("files") List<MultipartFile> files) {
        String result = files.stream()
                .map(f -> f.getOriginalFilename() + " (" + f.getSize() + " bytes)")
                .collect(Collectors.joining(", "));
        return ResponseEntity.ok("Uploaded " + files.size() + " files: " + result);
    }

    /**
     * 文件上传 + 表单字段混合接口。
     *
     * @param file        上传的文件
     * @param name        表单字段 name
     * @param description 表单字段 description
     * @return 文件和字段信息
     */
    @PostMapping("/upload/with-fields")
    public ResponseEntity<String> uploadWithFields(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam("description") String description) {
        return ResponseEntity.ok("Uploaded: " + file.getOriginalFilename()
                + ", size: " + file.getSize()
                + ", name: " + name
                + ", description: " + description);
    }

    /**
     * 大文件下载接口，生成 1MB 测试文件。
     *
     * @return 二进制文件流
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download() {
        byte[] data = new byte[1024 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        InputStreamResource resource = new InputStreamResource(
                new ByteArrayInputStream(data));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"testfile.bin\"")
                .contentLength(data.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}
