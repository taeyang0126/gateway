package com.lei.gateway.perf.util;

import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;

/**
 * 构建预热阶段 PopulationBuilder，预热期间请求不计入 RPS/P99 统计。
 * 预热 Scenario 与正式 Scenario 并列注入，正式 Scenario 通过 nothingFor 延迟启动。
 */
public final class WarmupHelper {

    private WarmupHelper() {
    }

    /**
     * 构建预热注入配置。
     * skipWarmup=true 时跳过预热并输出警告，返回 null。
     *
     * @param scenario       预热使用的 ScenarioBuilder
     * @param warmupUsers    预热并发用户数
     * @param warmupDuration 预热持续秒数
     * @param skipWarmup     是否跳过预热
     * @return 预热 PopulationBuilder，skipWarmup=true 时返回 null
     */
    public static PopulationBuilder build(ScenarioBuilder scenario, int warmupUsers,
            int warmupDuration, boolean skipWarmup) {
        if (skipWarmup) {
            System.out.println("已跳过预热，测试数据可能包含 JIT 冷启动噪声");
            return null;
        }
        if (warmupUsers <= 0 || warmupDuration <= 0) {
            System.out.printf("预热参数无效（users=%d, duration=%d），自动跳过预热%n",
                    warmupUsers, warmupDuration);
            return null;
        }

        System.out.printf("预热阶段已配置：%d 用户持续 %d 秒，预热完成后正式 Scenario 开始计数%n",
                warmupUsers, warmupDuration);
        return scenario.injectOpen(
                constantUsersPerSec(warmupUsers).during(Duration.ofSeconds(warmupDuration))
        );
    }
}
