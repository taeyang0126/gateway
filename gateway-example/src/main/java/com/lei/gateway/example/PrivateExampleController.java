package com.lei.gateway.example;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 需要网关认证后访问的示例接口。
 */
@RestController
@RequestMapping("/api/example/private")
public class PrivateExampleController {

    /**
     * 返回认证用户信息。
     */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> profile(
            @RequestHeader(value = "x-userId", required = false) String userId) {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "resource", "private-profile",
                "userId", userId == null ? "unknown" : userId));
    }
}
