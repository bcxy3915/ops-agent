package com.example.opsaiagent.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.metrics.entity.MetricSampleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标采样 Mapper
 */
@Mapper
public interface MetricSampleMapper extends BaseMapper<MetricSampleEntity> {
}
