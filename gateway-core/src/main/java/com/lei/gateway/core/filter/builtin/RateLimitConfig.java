package com.lei.gateway.core.filter.builtin;

import java.util.ArrayList;
import java.util.List;

/**
 * 限流过滤器配置。
 */
public class RateLimitConfig {

    private List<DimensionConfig> dimensions = new ArrayList<>();

    public List<DimensionConfig> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<DimensionConfig> dimensions) {
        this.dimensions = dimensions;
    }

    /** 单个限流维度配置。 */
    public static class DimensionConfig {

        private Dimension dimension;
        private int limit;
        private int windowSeconds;
        /** 桶容量上限，-1 表示等于 limit（不允许突发）。 */
        private int burstCapacity = -1;

        public Dimension getDimension() {
            return dimension;
        }

        public void setDimension(Dimension dimension) {
            this.dimension = dimension;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        /** 限流维度枚举。 */
        public enum Dimension {
            IP, ROUTE, USER_ID
        }
    }
}
