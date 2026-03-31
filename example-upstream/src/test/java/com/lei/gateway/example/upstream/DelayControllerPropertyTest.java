package com.lei.gateway.example.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.springframework.http.ResponseEntity;

/**
 * DelayController 属性测试。
 * Feature: gatling-performance-test, Property 6: 延迟接口响应不变量。
 */
class DelayControllerPropertyTest {

    private final DelayController controller = new DelayController();

    /**
     * Property 6a：对任意合法 ms（0 ~ 200），fixed 接口响应体 delay == ms，type == "fixed"，状态码 200。
     * 上限设为 200ms 避免测试超时。
     */
    @Property(tries = 100)
    void fixedDelay_responseInvariant(@ForAll @IntRange(min = 0, max = 200) int ms)
            throws InterruptedException {
        ResponseEntity<?> response = controller.fixed(ms);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("delay")).isEqualTo(ms);
        assertThat(body.get("type")).isEqualTo("fixed");
    }

    /**
     * Property 6b：对任意合法 min/max（0 <= min <= max <= 100），random 接口响应体 delay 在 [min, max]，
     * type == "random"，状态码 200。
     */
    @Property(tries = 100)
    void randomDelay_responseInvariant(
            @ForAll @IntRange(min = 0, max = 100) int min,
            @ForAll @IntRange(min = 0, max = 100) int max) throws InterruptedException {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        ResponseEntity<?> response = controller.random(lo, hi);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        int delay = (int) body.get("delay");
        assertThat(delay).isBetween(lo, hi);
        assertThat(body.get("type")).isEqualTo("random");
    }
}
