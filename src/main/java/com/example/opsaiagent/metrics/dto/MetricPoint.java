package com.example.opsaiagent.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个指标数据点（时间 + 值）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetricPoint {

    /**
     * 时间戳（毫秒）
     */
    private long timestamp;

    /**
     * 指标值
     */
    private double value;
}