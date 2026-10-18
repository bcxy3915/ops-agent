package com.example.opsaiagent.metrics.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.entity.MetricSampleEntity;
import com.example.opsaiagent.metrics.mapper.MetricSampleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 指标采样持久化存储。
 * 提供采样落库与按服务/指标/时间范围查询能力，供告警评估、故障快照、历史大盘使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricSampleStore {

    private final MetricSampleMapper mapper;

    /**
     * 保存一个采样点
     *
     * @param service    服务名
     * @param metric     指标名
     * @param timestampMs 采样时间戳（毫秒）
     * @param value      指标值
     */
    public void save(String service, String metric, long timestampMs, double value) {
        MetricSampleEntity entity = new MetricSampleEntity();
        entity.setService(service);
        entity.setMetric(metric);
        entity.setValue(value);
        entity.setSampledAt(toLocalDateTime(timestampMs));
        try {
            mapper.insert(entity);
        } catch (Exception e) {
            // 采样落库失败不影响采样主流程，记录日志即可
            log.warn("指标落库失败: {}-{}: {}", service, metric, e.getMessage());
        }
    }

    /**
     * 批量保存一个服务的多个指标采样点
     *
     * @param service 服务名
     * @param points  指标名 -> 值（时间戳统一用同一采样时刻）
     * @param timestampMs 采样时间戳（毫秒）
     */
    public void saveBatch(String service, long timestampMs, java.util.Map<String, Double> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        LocalDateTime sampledAt = toLocalDateTime(timestampMs);
        for (var entry : points.entrySet()) {
            MetricSampleEntity entity = new MetricSampleEntity();
            entity.setService(service);
            entity.setMetric(entry.getKey());
            entity.setValue(entry.getValue());
            entity.setSampledAt(sampledAt);
            try {
                mapper.insert(entity);
            } catch (Exception e) {
                log.warn("指标落库失败: {}-{}: {}", service, entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * 按服务 + 指标 + 时间范围查询历史采样
     *
     * @param service  服务名
     * @param metric   指标名
     * @param startMs  开始时间戳（毫秒）
     * @param endMs    结束时间戳（毫秒）
     * @return 指标数据点列表（按时间升序）
     */
    public List<MetricPoint> query(String service, String metric, long startMs, long endMs) {
        List<MetricSampleEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<MetricSampleEntity>()
                        .eq(MetricSampleEntity::getService, service)
                        .eq(MetricSampleEntity::getMetric, metric)
                        .between(MetricSampleEntity::getSampledAt, toLocalDateTime(startMs), toLocalDateTime(endMs))
                        .orderByAsc(MetricSampleEntity::getSampledAt));
        return rows.stream()
                .map(r -> new MetricPoint(
                        r.getSampledAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        r.getValue()))
                .toList();
    }

    /**
     * 查询某服务在时间范围内最近的一个采样值
     *
     * @param service 服务名
     * @param metric  指标名
     * @param endMs   截止时间戳（毫秒）
     * @return 最近采样值；无数据返回 null
     */
    public MetricPoint queryLatest(String service, String metric, long endMs) {
        List<MetricSampleEntity> rows = mapper.selectList(
                new LambdaQueryWrapper<MetricSampleEntity>()
                        .eq(MetricSampleEntity::getService, service)
                        .eq(MetricSampleEntity::getMetric, metric)
                        .le(MetricSampleEntity::getSampledAt, toLocalDateTime(endMs))
                        .orderByDesc(MetricSampleEntity::getSampledAt)
                        .last("LIMIT 1"));
        if (rows.isEmpty()) {
            return null;
        }
        MetricSampleEntity r = rows.get(0);
        return new MetricPoint(
                r.getSampledAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                r.getValue());
    }

    /**
     * 删除指定时间之前的采样数据（保留策略）
     *
     * @param beforeMs 时间戳（毫秒），早于该时间的记录将被删除
     * @return 删除条数
     */
    public int deleteOlderThan(long beforeMs) {
        return mapper.delete(new LambdaQueryWrapper<MetricSampleEntity>()
                .lt(MetricSampleEntity::getSampledAt, toLocalDateTime(beforeMs)));
    }

    private LocalDateTime toLocalDateTime(long timestampMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneId.systemDefault());
    }
}
