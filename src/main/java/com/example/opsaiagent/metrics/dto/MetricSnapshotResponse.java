package com.example.opsaiagent.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指标实时快照响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricSnapshotResponse {

    /**
     * 服务名
     */
    private String service;

    /**
     * 时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 关键指标快照
     */
    private Snapshot snapshot;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Snapshot {
        /**
         * CPU 使用率 0-1
         */
        private double cpu;

        /**
         * 已用内存（字节）
         */
        private long memoryUsed;

        /**
         * 最大内存（字节）
         */
        private long memoryMax;

        /**
         * HTTP 请求数（每分钟）
         */
        private long httpCount;

        /**
         * HTTP 错误率 0-1
         */
        private double httpErrorRate;

        /**
         * HTTP 平均耗时（秒）
         */
        private double httpAvgDuration;

        /**
         * 活跃线程数
         */
        private int threadCount;

        /**
         * GC 暂停时间（毫秒）
         */
        private double gcPause;

        /**
         * 数据库活跃连接数
         */
        private int hikariActive;

        /**
         * 数据库等待连接数
         */
        private int hikariPending;
    }
}