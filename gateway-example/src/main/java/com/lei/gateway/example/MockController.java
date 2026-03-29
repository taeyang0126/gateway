package com.lei.gateway.example;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 零延迟 Mock 控制器，为 Gatling 性能测试提供网关自身吞吐量基线接口。
 */
@RestController
@RequestMapping("/api/example")
public class MockController {

    /**
     * 零延迟接口，直接返回固定响应体，无任何业务逻辑。
     *
     * @return 固定响应 {"status":"ok"}
     */
    @GetMapping("/mock")
    public ResponseEntity<Map<String, Object>> mock() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
