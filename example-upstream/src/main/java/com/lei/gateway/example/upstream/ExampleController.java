package com.lei.gateway.example.upstream;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.PathResource;
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
 * Mock 上游服务控制器，提供普通 REST 接口、文件上传和文件下载端点。
 */
@RestController
@RequestMapping("/api/example")
public class ExampleController {

    private static final Logger log = LoggerFactory.getLogger(ExampleController.class);

    /**
     * 上传文件存储目录。
     */
    private static final Path UPLOAD_DIR = Paths.get("example-upstream/uploads");

    /**
     * 下载的默认文件名。
     */
    private static final String DOWNLOAD_FILE = "testfile.bin";

    /**
     * 简单 GET 接口。
     */
    @GetMapping("/hello")
    public ResponseEntity<String> hello(HttpServletRequest request) {
        if (log.isInfoEnabled()) {
            log.info("GET /hello headers={}", headersOf(request));
        }
        return ResponseEntity.ok("Hello from upstream!");
    }

    /**
     * POST echo 接口，原样返回请求体。
     */
    @PostMapping("/echo")
    public ResponseEntity<String> echo(@RequestBody String body, HttpServletRequest request) {
        if (log.isInfoEnabled()) {
            log.info("POST /echo body.length={} headers={}", body.length(), headersOf(request));
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 单文件上传接口，保存到 UPLOAD_DIR。
     */
    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file,
            HttpServletRequest request)
            throws IOException {
        String filename = file.getOriginalFilename();
        if (log.isInfoEnabled()) {
            log.info("POST /upload filename={} size={} headers={}", filename, file.getSize(),
                    headersOf(request));
        }
        Path saved = saveFile(file);
        if (log.isInfoEnabled()) {
            log.info("POST /upload saved to {}", saved.toAbsolutePath());
        }
        return ResponseEntity.ok("Uploaded: " + filename + ", size: " + file.getSize());
    }

    /**
     * 多文件上传接口，逐个保存到 UPLOAD_DIR。
     */
    @PostMapping("/upload/multi")
    public ResponseEntity<String> uploadMulti(@RequestParam("files") List<MultipartFile> files,
            HttpServletRequest request)
            throws IOException {
        if (log.isInfoEnabled()) {
            log.info("POST /upload/multi count={} headers={}", files.size(), headersOf(request));
        }
        StringBuilder sb = new StringBuilder();
        for (MultipartFile file : files) {
            Path saved = saveFile(file);
            if (log.isInfoEnabled()) {
                log.info("POST /upload/multi saved {} -> {}", file.getOriginalFilename(),
                        saved.toAbsolutePath());
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(file.getOriginalFilename()).append(" (").append(file.getSize())
                    .append(" bytes)");
        }
        return ResponseEntity.ok("Uploaded " + files.size() + " files: " + sb);
    }

    /**
     * 文件上传 + 表单字段混合接口，保存到 UPLOAD_DIR。
     */
    @PostMapping("/upload/with-fields")
    public ResponseEntity<String> uploadWithFields(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam("description") String description,
            HttpServletRequest request) throws IOException {
        if (log.isInfoEnabled()) {
            log.info("POST /upload/with-fields filename={} size={} name={} description={} headers={}",
                    file.getOriginalFilename(), file.getSize(), name, description, headersOf(request));
        }
        Path saved = saveFile(file);
        if (log.isInfoEnabled()) {
            log.info("POST /upload/with-fields saved to {}", saved.toAbsolutePath());
        }
        return ResponseEntity.ok("Uploaded: " + file.getOriginalFilename()
                + ", size: " + file.getSize()
                + ", name: " + name
                + ", description: " + description);
    }

    /**
     * 文件下载接口，从 UPLOAD_DIR 读取 testfile.bin；若不存在则自动生成并保存。
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(HttpServletRequest request) throws IOException {
        if (log.isInfoEnabled()) {
            log.info("GET /download headers={}", headersOf(request));
        }
        Path filePath = UPLOAD_DIR.resolve(DOWNLOAD_FILE);
        if (!Files.exists(filePath)) {
            if (log.isInfoEnabled()) {
                log.info("GET /download testfile.bin not found, generating...");
            }
            Files.createDirectories(UPLOAD_DIR);
            byte[] data = new byte[10 * 1024 * 1024];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 256);
            }
            Files.write(filePath, data);
            if (log.isInfoEnabled()) {
                log.info("GET /download generated testfile.bin at {}", filePath.toAbsolutePath());
            }
        }
        long fileSize = Files.size(filePath);
        if (log.isInfoEnabled()) {
            log.info("GET /download serving {} bytes from {}", fileSize, filePath.toAbsolutePath());
        }
        Resource resource = new PathResource(filePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + DOWNLOAD_FILE + "\"")
                .contentLength(fileSize)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    /**
     * 将 MultipartFile 保存到 UPLOAD_DIR，文件名取 originalFilename。
     */
    private Path saveFile(MultipartFile file) throws IOException {
        Files.createDirectories(UPLOAD_DIR);
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "upload_" + System.currentTimeMillis();
        }
        // 防止路径穿越
        Path target = UPLOAD_DIR.resolve(Paths.get(filename).getFileName()).normalize();
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * 将请求头格式化为字符串，用于日志输出。
     */
    private static String headersOf(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder("{");
        Collections.list(request.getHeaderNames()).forEach(name ->
                sb.append(name).append("=").append(request.getHeader(name)).append(", "));
        if (sb.length() > 1) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("}");
        return sb.toString();
    }
}
