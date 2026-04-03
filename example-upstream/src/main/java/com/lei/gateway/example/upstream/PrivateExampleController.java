package com.lei.gateway.example.upstream;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PrivateExampleController.class);

    /**
     * 返回认证用户信息。
     */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> profile(
            @RequestHeader(value = "x-userId", required = false) String userId,
            HttpServletRequest request) {
        if (log.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("{");
            Collections.list(request.getHeaderNames()).forEach(name ->
                    sb.append(name).append("=").append(request.getHeader(name)).append(", "));
            if (sb.length() > 1) {
                sb.setLength(sb.length() - 2);
            }
            sb.append("}");
            log.info("GET /profile headers={}", sb);
        }
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "resource", "private-profile",
                "userId", userId == null ? "unknown" : userId));
    }
}
