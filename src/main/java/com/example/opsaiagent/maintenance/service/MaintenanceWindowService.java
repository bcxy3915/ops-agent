package com.example.opsaiagent.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.alert.dto.AlertEventStatus;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.alert.service.AlertEventService;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.maintenance.dto.MaintenanceWindowCreateRequest;
import com.example.opsaiagent.maintenance.dto.MaintenanceWindowResponse;
import com.example.opsaiagent.maintenance.entity.MaintenanceWindowEntity;
import com.example.opsaiagent.maintenance.mapper.MaintenanceWindowMapper;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 维护窗口服务（P1 规格 5）
 * - 设置窗口：对存量非 RESOLVED 告警批量静默，并落窗口记录
 * - 窗口判断：AlertEvaluator 在触发新告警前询问 isServiceInWindow，维护期内新告警落库但压制通知/升级
 * - 到期扫描：expireDueWindows 置 ended_at 并返回到期服务，由 AlertEvaluator 恢复压制告警并重新评估
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceWindowService {

    private final MaintenanceWindowMapper windowMapper;
    private final AlertEventMapper alertEventMapper;
    private final AlertEventService alertEventService;

    /** 维护静默原因前缀，用于识别维护窗口压制的告警 */
    public static final String SILENCE_REASON_PREFIX = "维护窗口：";

    /**
     * 创建维护窗口：多选服务 + 原因 + 到期时间。
     * 每个服务一行记录；同时对服务下存量非 RESOLVED 告警批量静默（复用单条静默语义）。
     */
    @Transactional
    public List<MaintenanceWindowResponse> create(MaintenanceWindowCreateRequest request) {
        LocalDateTime now = LocalDateTime.now();
        if (request.getUntil() == null || !request.getUntil().isAfter(now)) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_INVALID,
                    "维护窗口到期时间必须晚于当前时间");
        }
        String tenantId = currentTenantId();
        Long deptId = SecurityUtils.getCurrentDeptId();
        List<String> services = request.getServices().stream().distinct().toList();
        List<MaintenanceWindowResponse> created = new ArrayList<>();
        for (String service : services) {
            MaintenanceWindowEntity window = new MaintenanceWindowEntity();
            window.setService(service);
            window.setTenantId(tenantId);
            window.setDeptId(deptId);
            window.setReason(request.getReason());
            window.setUntil(request.getUntil());
            window.setNote(request.getNote());
            window.setCreatedBy(SecurityUtils.getCurrentUsername());
            window.setCreatedAt(now);
            windowMapper.insert(window);
            created.add(toResponse(window));
        }
        silenceExistingAlerts(services, tenantId, request.getReason(), request.getUntil());
        return created;
    }

    /**
     * 存量告警批量静默：服务下所有 FIRING / ACKNOWLEDGED / 已 SILENCED 事件
     * 统一置为维护窗口到期时间，reason 打上维护窗口前缀便于到期恢复识别。
     */
    private void silenceExistingAlerts(List<String> services, String tenantId,
                                       String reason, LocalDateTime until) {
        if (services.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<AlertEventEntity> qw = new LambdaQueryWrapper<AlertEventEntity>()
                .in(AlertEventEntity::getService, services)
                .in(AlertEventEntity::getStatus,
                        AlertEventStatus.FIRING.name(),
                        AlertEventStatus.ACKNOWLEDGED.name(),
                        AlertEventStatus.SILENCED.name());
        if (StringUtils.hasText(tenantId)) {
            qw.eq(AlertEventEntity::getTenantId, tenantId);
        }
        List<AlertEventEntity> events = alertEventMapper.selectList(qw);
        for (AlertEventEntity event : events) {
            try {
                if (AlertEventStatus.SILENCED.name().equals(event.getStatus())) {
                    // 已静默：仅顺延到期时间并标记维护来源
                    event.setSilencedUntil(until);
                    event.setSilencedReason(SILENCE_REASON_PREFIX + reason);
                    event.setUpdatedAt(LocalDateTime.now());
                    alertEventMapper.updateById(event);
                } else {
                    alertEventService.silence(event.getId(), SILENCE_REASON_PREFIX + reason, until, null);
                }
            } catch (BusinessException ex) {
                log.warn("维护窗口静默存量告警失败 eventId={}: {}", event.getId(), ex.getMessage());
            }
        }
    }

    /**
     * 查询窗口列表；activeOnly 为 true 时只返回未结束且未到期窗口。
     */
    public List<MaintenanceWindowResponse> list(boolean activeOnly) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<MaintenanceWindowEntity> qw = new LambdaQueryWrapper<MaintenanceWindowEntity>()
                .orderByDesc(MaintenanceWindowEntity::getCreatedAt);
        String tenantId = SecurityUtils.getCurrentTenantId();
        if (StringUtils.hasText(tenantId)) {
            qw.eq(MaintenanceWindowEntity::getTenantId, tenantId);
        }
        List<MaintenanceWindowEntity> windows = windowMapper.selectList(qw);
        return windows.stream()
                .filter(w -> !activeOnly || isWindowActive(w, now))
                .map(this::toResponse)
                .toList();
    }

    /**
     * 提前结束维护窗口（ended_at 置当前时间）
     */
    public MaintenanceWindowResponse end(Long id) {
        MaintenanceWindowEntity window = windowMapper.selectById(id);
        if (window == null || !canAccess(window)) {
            throw new BusinessException(ErrorCode.MAINTENANCE_WINDOW_NOT_FOUND, "维护窗口不存在: " + id);
        }
        if (window.getEndedAt() != null) {
            throw new BusinessException(ErrorCode.ALERT_EVENT_INVALID, "维护窗口已结束: " + id);
        }
        window.setEndedAt(LocalDateTime.now());
        windowMapper.updateById(window);
        return toResponse(window);
    }

    /**
     * 判断服务当前是否处于有效维护窗口（未结束且未到期）
     */
    public boolean isServiceInWindow(String service, LocalDateTime now) {
        return isServiceInWindow(null, service, now);
    }

    /**
     * 判断服务在指定租户下是否处于有效维护窗口。
     *
     * @param tenantId 租户 ID；为空时保持旧全局查询
     * @param service  服务名
     * @param now      当前时间
     * @return true=维护中
     */
    public boolean isServiceInWindow(String tenantId, String service, LocalDateTime now) {
        LambdaQueryWrapper<MaintenanceWindowEntity> qw = new LambdaQueryWrapper<MaintenanceWindowEntity>()
                .eq(MaintenanceWindowEntity::getService, service)
                .isNull(MaintenanceWindowEntity::getEndedAt)
                .gt(MaintenanceWindowEntity::getUntil, now)
                .last("LIMIT 1");
        if (StringUtils.hasText(tenantId)) {
            qw.eq(MaintenanceWindowEntity::getTenantId, tenantId);
        }
        List<MaintenanceWindowEntity> windows = windowMapper.selectList(qw);
        return !windows.isEmpty();
    }

    /**
     * 到期扫描：把已到计划时间的窗口置 ended_at，返回涉及的服务列表。
     * 调用方（AlertEvaluator）据此恢复维护压制告警并重新评估是否通知。
     */
    public List<String> expireDueWindows(LocalDateTime now) {
        List<MaintenanceWindowEntity> due = windowMapper.selectList(
                new LambdaQueryWrapper<MaintenanceWindowEntity>()
                        .isNull(MaintenanceWindowEntity::getEndedAt)
                        .le(MaintenanceWindowEntity::getUntil, now));
        Set<String> services = due.stream().map(MaintenanceWindowEntity::getService).collect(Collectors.toSet());
        for (MaintenanceWindowEntity window : due) {
            window.setEndedAt(now);
            windowMapper.updateById(window);
        }
        return new ArrayList<>(services);
    }

    private boolean isWindowActive(MaintenanceWindowEntity window, LocalDateTime now) {
        return window.getEndedAt() == null && window.getUntil().isAfter(now);
    }

    private String currentTenantId() {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return StringUtils.hasText(tenantId) ? tenantId : "t-default";
    }

    private boolean canAccess(MaintenanceWindowEntity window) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        return !StringUtils.hasText(tenantId)
                || tenantId.equals(window.getTenantId())
                || "0".equals(tenantId);
    }

    private MaintenanceWindowResponse toResponse(MaintenanceWindowEntity window) {
        return MaintenanceWindowResponse.builder()
                .id(window.getId())
                .service(window.getService())
                .tenantId(window.getTenantId())
                .deptId(window.getDeptId())
                .reason(window.getReason())
                .until(window.getUntil())
                .note(window.getNote())
                .createdBy(window.getCreatedBy())
                .createdAt(window.getCreatedAt())
                .endedAt(window.getEndedAt())
                .active(isWindowActive(window, LocalDateTime.now()))
                .build();
    }
}
