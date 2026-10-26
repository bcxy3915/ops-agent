package com.example.opsaiagent.metrics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.opsaiagent.metrics.entity.MetricSampleEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 指标采样 Mapper
 */
@Mapper
public interface MetricSampleMapper extends BaseMapper<MetricSampleEntity> {

    /**
     * 批量插入采样点（一次 SQL，降低写放大）。
     *
     * @param entities 采样点列表
     * @return 影响行数
     */
    @Insert("<script>" +
            "INSERT INTO metric_sample (service, tenant_id, metric, value, sampled_at) VALUES " +
            "<foreach collection='list' item='e' separator=','>" +
            "(#{e.service}, #{e.tenantId}, #{e.metric}, #{e.value}, #{e.sampledAt})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<MetricSampleEntity> entities);
}
