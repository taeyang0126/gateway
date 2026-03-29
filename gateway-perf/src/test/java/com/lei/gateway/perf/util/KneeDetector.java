package com.lei.gateway.perf.util;

import java.util.List;

/**
 * 膝点探测逻辑工具类。
 * 封装停止条件判断，便于属性测试验证不变量。
 */
public final class KneeDetector {

    /** P99 退化阈值：当前阶段 P99 超过前一阶段 P99 的 150% 时触发停止。 */
    public static final double P99_SPIKE_RATIO = 1.50;

    /** 错误率停止阈值：超过 1% 时触发停止。 */
    public static final double ERROR_RATE_THRESHOLD = 0.01;

    private KneeDetector() {
    }

    /**
     * 判断是否应停止递增负载。
     * 停止条件：当前阶段 P99 超过前一阶段 P99 的 150%，或错误率超过 1%。
     *
     * @param prevP99Ms   前一阶段 P99（ms），首阶段传 0 表示无前一阶段
     * @param currentP99Ms 当前阶段 P99（ms）
     * @param errorRate   当前阶段错误率（0.0 ~ 1.0）
     * @return true 表示应停止递增
     */
    public static boolean shouldStop(long prevP99Ms, long currentP99Ms, double errorRate) {
        if (errorRate > ERROR_RATE_THRESHOLD) {
            return true;
        }
        if (prevP99Ms > 0 && currentP99Ms > prevP99Ms * P99_SPIKE_RATIO) {
            return true;
        }
        return false;
    }

    /**
     * 从阶段指标序列中找到膝点所在阶段索引。
     * 返回第一个触发停止条件的阶段索引；若所有阶段均未触发，返回最后一个阶段索引。
     *
     * @param stages 各阶段指标列表（按负载递增顺序排列）
     * @return 膝点阶段索引（0-based）
     */
    public static int findKneeIndex(List<StageMetrics> stages) {
        if (stages == null || stages.isEmpty()) {
            return 0;
        }
        long prevP99 = 0;
        for (int ii = 0; ii < stages.size(); ii++) {
            StageMetrics stage = stages.get(ii);
            if (shouldStop(prevP99, stage.p99Ms(), stage.errorRate())) {
                return ii;
            }
            prevP99 = stage.p99Ms();
        }
        return stages.size() - 1;
    }

    /**
     * 单阶段指标数据。
     *
     * @param rps       该阶段 RPS
     * @param p99Ms     该阶段 P99（ms）
     * @param errorRate 该阶段错误率（0.0 ~ 1.0）
     */
    public record StageMetrics(double rps, long p99Ms, double errorRate) {
    }
}
