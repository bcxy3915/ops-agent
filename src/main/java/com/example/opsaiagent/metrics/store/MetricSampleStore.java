package com.example.opsaiagent.metrics.store;

import java.util.Map;
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
import java.util.ArrayList;
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
     * 保存一个采样点（兼容旧调用，租户由数据库默认值补齐）
     *
     * @param service    服务名
     * @param metric     指标名
     * @param timestampMs 采样时间戳（毫秒）
     * @param value      指标值
     */
    public void save(String service, String metric, long timestampMs, double value) {
        save(null, service, metric, timestampMs, value);
    }

    /**
     * 保存一个采样点
     *
     * @param tenantId   租户 ID
     * @param service    服务名
     * @param metric     指标名
     * @param timestampMs 采样时间戳（毫秒）
     * @param value      指标值
     */
    public void save(String tenantId, String service, String metric, long timestampMs, double value) {
        MetricSampleEntity entity = new MetricSampleEntity();
        entity.setTenantId(tenantId);
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
     * 批量保存一个服务的多个指标采样点（兼容旧调用）
     *
     * @param service 服务名
     * @param points  指标名 -> 值（时间戳统一用同一采样时刻）
     * @param timestampMs 采样时间戳（毫秒）
     */
    public void saveBatch(String service, long timestampMs, Map<String, Double> points) {
        saveBatch(null, service, timestampMs, points);
    }

    /**
     * 批量保存一个服务的多个指标采样点
     *
     * @param tenantId 租户 ID
     * @param service 服务名
     * @param points  指标名 -> 值（时间戳统一用同一采样时刻）
     * @param timestampMs 采样时间戳（毫秒）
     */
    public void saveBatch(String tenantId, String service, long timestampMs,
                          Map<String, Double> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        LocalDateTime sampledAt = toLocalDateTime(timestampMs);
        List<MetricSampleEntity> entities = new ArrayList<>(points.size());
        for (var entry : points.entrySet()) {
            MetricSampleEntity entity = new MetricSampleEntity();
            entity.setTenantId(tenantId);
            entity.setService(service);
            entity.setMetric(entry.getKey());
            entity.setValue(entry.getValue());
            entity.setSampledAt(sampledAt);
            entities.add(entity);
        }
        try {
            mapper.insertBatch(entities);
        } catch (Exception e) {
            log.warn("指标批量落库失败: {}-{}: {}", service, points.keySet(), e.getMessage());
        }
    }

    /**
     * 按服务 + 指标 + 时间范围查询历史采样（兼容旧调用，不限制租户）
     *
     * @param service  服务名
     * @param metric   指标名
     * @param startMs  开始时间戳（毫秒）
     * @param endMs    结束时间戳（毫秒）
     * @return 指标数据点列表（按时间升序）
     */
    public List<MetricPoint> query(String service, String metric, long startMs, long endMs) {
        return query(null, service, metric, startMs, endMs);
    }

    /**
     * 按租户 + 服务 + 指标 + 时间范围查询历史采样
     *
     * @param tenantId 租户 ID；为空不限制
     * @param service  服务名
     * @param metric   指标名
     * @param startMs  开始时间戳（毫秒）
     * @param endMs    结束时间戳（毫秒）
     * @return 指标数据点列表（按时间升序）
     */
    public List<MetricPoint> query(String tenantId, String service, String metric,
                                   long startMs, long endMs) {
        LambdaQueryWrapper<MetricSampleEntity> wrapper = new LambdaQueryWrapper<MetricSampleEntity>()
                .eq(MetricSampleEntity::getService, service)
                .eq(MetricSampleEntity::getMetric, metric)
                .between(MetricSampleEntity::getSampledAt, toLocalDateTime(startMs), toLocalDateTime(endMs))
                .orderByAsc(MetricSampleEntity::getSampledAt);
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(MetricSampleEntity::getTenantId, tenantId);
        }
        List<MetricSampleEntity> rows = mapper.selectList(wrapper);
        return rows.stream()
                .map(r -> new MetricPoint(
                        r.getSampledAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        r.getValue()))
                .toList();
    }

    /**
     * 查询某服务在时间范围内最近的一个采样值（兼容旧调用）
     *
     * @param service 服务名
     * @param metric  指标名
     * @param endMs   截止时间戳（毫秒）
     * @return 最近采样值；无数据返回 null
     */
    public MetricPoint queryLatest(String service, String metric, long endMs) {
        return queryLatest(null, service, metric, endMs);
    }

    /**
     * 查询某服务在时间范围内最近的一个采样值
     *
     * @param tenantId 租户 ID；为空不限制
     * @param service 服务名
     * @param metric  指标名
     * @param endMs   截止时间戳（毫秒）
     * @return 最近采样值；无数据返回 null
     */
    public MetricPoint queryLatest(String tenantId, String service, String metric, long endMs) {
        LambdaQueryWrapper<MetricSampleEntity> wrapper = new LambdaQueryWrapper<MetricSampleEntity>()
                .eq(MetricSampleEntity::getService, service)
                .eq(MetricSampleEntity::getMetric, metric)
                .le(MetricSampleEntity::getSampledAt, toLocalDateTime(endMs))
                .orderByDesc(MetricSampleEntity::getSampledAt)
                .last("LIMIT 1");
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(MetricSampleEntity::getTenantId, tenantId);
        }
        List<MetricSampleEntity> rows = mapper.selectList(wrapper);
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

    /**
     * 按明确租户有界查询半开时间窗，避免相邻窗口边界重复和历史结果无限增长。
     * @param tenantId 目标服务所属租户，不能为空
     * @param service 服务名称
     * @param metric 已校验的存储指标名
     * @param startMs 窗口起点，包含
     * @param endMs 窗口终点，不包含
     * @param limit 返回行数上限，范围 1～721
     * @return 时间升序的采样点；数据库空值以非有限值保留，交由证据层判为无效
     */
    public List<MetricPoint> queryBounded(String tenantId, String service, String metric,
                                         long startMs, long endMs, int limit) {
        if (tenantId == null || tenantId.isBlank() || startMs >= endMs || limit < 1 || limit > 721) {
            throw new IllegalArgumentException("历史证据查询必须指定租户、有效时间窗及行数上限");
        }
        var wrapper = new LambdaQueryWrapper<MetricSampleEntity>()
                .eq(MetricSampleEntity::getTenantId, tenantId)
                .eq(MetricSampleEntity::getService, service)
                .eq(MetricSampleEntity::getMetric, metric)
                .ge(MetricSampleEntity::getSampledAt, toLocalDateTime(startMs))
                .lt(MetricSampleEntity::getSampledAt, toLocalDateTime(endMs))
                .orderByAsc(MetricSampleEntity::getSampledAt)
                .orderByAsc(MetricSampleEntity::getId)
                .last("LIMIT " + limit);
        return mapper.selectList(wrapper).stream().map(row -> new MetricPoint(
                row.getSampledAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                row.getValue() == null ? Double.NaN : row.getValue())).toList();
    }

    /**
     * 将采样时间戳转换为既有数据库时间口径。
     * @param timestampMs 时间戳，单位毫秒
     * @return 本地日期时间
     */
    private LocalDateTime toLocalDateTime(long timestampMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneId.systemDefault());
    }
}
