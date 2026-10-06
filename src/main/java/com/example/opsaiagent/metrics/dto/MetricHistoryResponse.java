package com.example.opsaiagent.metrics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 指标历史响应
 */
@Data
@Builder
public class MetricHistoryResponse {

    /**
     * 服务名
     */
    private String service;

    /**
     * 时间范围
     */
    private String range;

    /**
     * 时序数据：key 是指标名，value 是数据点列表
     */
    private Map<String, List<MetricPoint>> series;
}