package com.example.opsaiagent.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标采样记录（持久化，供告警/大盘/快照使用）
 */
@Data
@TableName("metric_sample")
public class MetricSampleEntity {

    /**
     * ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 服务名
     */
    private String service;

    /**
     * 指标名
     */
    private String metric;

    /**
     * 指标值
     */
    private Double value;

    /**
     * 采样时间
     */
    private LocalDateTime sampledAt;
}
