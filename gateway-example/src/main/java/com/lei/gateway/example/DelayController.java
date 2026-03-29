package com.lei.gateway.example;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 延迟模拟控制器，为 Gatling 性能测试提供可参数化的延迟接口。
 *
 * <p>注意：网关 upstream 不支持路径前缀，因此 perf-slow-timeout 路由的 upstream 指向
 * {@code http://localhost:8082}，通过 {@code /api/perf/slow} 别名路径访问慢接口。
 */
@RestController
public class DelayController {

    /**
     * 固定延迟接口，等待 ms 毫秒后返回。
     *
     * @param ms 延迟毫秒数，默认 50，必须非负
     * @return 延迟信息
     * @throws InterruptedException 线程中断时抛出
     */
    @GetMapping("/api/example/delay/fixed")
    public ResponseEntity<Map<String, Object>> fixed(
            @RequestParam(name = "ms", defaultValue = "50") int ms) throws InterruptedException {
        if (ms < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "ms must be non-negative"));
        }
        Thread.sleep(ms);
        return ResponseEntity.ok(Map.of("delay", ms, "type", "fixed"));
    }

    /**
     * 随机延迟接口，在 [min, max] 范围内均匀随机等待后返回。
     *
     * @param min 最小延迟毫秒数，默认 0，必须非负
     * @param max 最大延迟毫秒数，默认 100，必须 >= min
     * @return 实际延迟信息
     * @throws InterruptedException 线程中断时抛出
     */
    @GetMapping("/api/example/delay/random")
    public ResponseEntity<Map<String, Object>> random(
            @RequestParam(name = "min", defaultValue = "0") int min,
            @RequestParam(name = "max", defaultValue = "100") int max) throws InterruptedException {
        if (min < 0 || max < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "min and max must be non-negative"));
        }
        if (min > max) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "min must not exceed max"));
        }
        int actualMs = (min == max) ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
        Thread.sleep(actualMs);
        return ResponseEntity.ok(Map.of("delay", actualMs, "type", "random"));
    }

    /**
     * 慢接口，固定等待 500ms 后返回。
     * 同时映射 {@code /api/perf/slow}，供 perf-slow-timeout 路由使用。
     *
     * @return 延迟信息
     * @throws InterruptedException 线程中断时抛出
     */
    @GetMapping({"/api/example/delay/slow", "/api/perf/slow"})
    public ResponseEntity<Map<String, Object>> slow() throws InterruptedException {
        Thread.sleep(500);
        return ResponseEntity.ok(Map.of("delay", 500, "type", "slow"));
    }
}
