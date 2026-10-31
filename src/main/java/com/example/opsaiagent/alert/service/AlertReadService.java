package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertEventStatus;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.entity.AlertReadEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.alert.mapper.AlertReadMapper;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户告警已读服务（P0 决策 1：已读 ≠ 已处理）
 * - 已读持久化到 user_alert_read 表，刷新后保持已读
 * - 未读计数口径（规格 1.2）：status IN (FIRING, ACKNOWLEDGED, SILENCED)
 *   且当前用户未读（无 read 记录 或 read_at < fired_at —— 状态回退视为新事件）
 * - 标记已读不影响告警状态（ACK 走独立接口）
 */
@Service
@RequiredArgsConstructor
public class AlertReadService {

    private final AlertReadMapper readMapper;
    private final AlertEventMapper eventMapper;

    /**
     * 标记单条已读
     */
    public void markRead(Long alertEventId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        readMapper.insertIgnore(currentTenantId(), userId, alertEventId);
    }

    /**
     * 批量标记已读（幂等）
     */
    public void markAllRead(List<Long> ids, String userId) {
        if (userId == null || userId.isBlank() || ids == null || ids.isEmpty()) {
            return;
        }
        String tenantId = currentTenantId();
        for (Long id : ids) {
            readMapper.insertIgnore(tenantId, userId, id);
        }
    }

    /**
     * 当前用户未读活跃告警数（铃铛 badge 数据源，规格 1.2 口径）
     */
    public int unreadCount(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        List<AlertEventEntity> pending = eventMapper.selectList(
                new LambdaQueryWrapper<AlertEventEntity>()
                        .eq(AlertEventEntity::getTenantId, currentTenantId())
                        .in(AlertEventEntity::getStatus,
                                AlertEventStatus.FIRING.name(),
                                AlertEventStatus.ACKNOWLEDGED.name(),
                                AlertEventStatus.SILENCED.name()));
        if (pending.isEmpty()) {
            return 0;
        }
        List<Long> ids = pending.stream().map(AlertEventEntity::getId).toList();
        Map<Long, LocalDateTime> readMap = readMap(userId, ids);
        int unread = 0;
        for (AlertEventEntity e : pending) {
            LocalDateTime readAt = readMap.get(e.getId());
            if (readAt == null || (e.getFiredAt() != null && readAt.isBefore(e.getFiredAt()))) {
                unread++;
            }
        }
        return unread;
    }

    /**
     * 查询当前用户对一批事件的已读时间映射（eventId -> readAt）
     */
    public Map<Long, LocalDateTime> readMap(String userId, Collection<Long> eventIds) {
        Map<Long, LocalDateTime> result = new HashMap<>();
        if (userId == null || userId.isBlank() || eventIds == null || eventIds.isEmpty()) {
            return result;
        }
        List<AlertReadEntity> rows = readMapper.selectList(
                new LambdaQueryWrapper<AlertReadEntity>()
                        .eq(AlertReadEntity::getTenantId, currentTenantId())
                        .eq(AlertReadEntity::getUserId, userId)
                        .in(AlertReadEntity::getAlertEventId, eventIds));
        for (AlertReadEntity row : rows) {
            result.put(row.getAlertEventId(), row.getReadAt());
        }
        return result;
    }

    /**
     * 查询当前用户对一批事件中哪些"有效已读"（readAt >= firedAt）
     */
    public Set<Long> readIds(String userId, Collection<Long> eventIds, Map<Long, LocalDateTime> firedAtMap) {
        Set<Long> result = new HashSet<>();
        Map<Long, LocalDateTime> map = readMap(userId, eventIds);
        for (Map.Entry<Long, LocalDateTime> entry : map.entrySet()) {
            LocalDateTime firedAt = firedAtMap.get(entry.getKey());
            if (firedAt == null || !entry.getValue().isBefore(firedAt)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /** 当前租户（照项目惯例：登录上下文取，空时兜底引导租户） */
    private String currentTenantId() {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return StringUtils.hasText(tenantId) ? tenantId : "t-default";
    }
}
