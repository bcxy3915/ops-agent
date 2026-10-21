package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.dto.AlertEventResponse;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.util.SecurityUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertEventService 单元测试
 * 覆盖（P0 规格 2/3）：
 * - 确认自动认领 acknowledge（记 acknowledgedBy/At）
 * - 独立认领 claim（FIRING/SILENCED 自动转 ACK、接管覆盖 owner）
 * - 手动恢复 resolve（记 resolvedBy/备注、截断）
 * - 静默 silence（校验 >15min / ≤7天、记 silencedFrom/silencedAt/备注）
 * - 解除静默 unsilence（回原状态、清字段保留时间线）
 * - 批量确认 / 静默到期自动恢复 / toResponse 已读填充
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警事件服务")
class AlertEventServiceTest {

    @Mock
    private AlertEventMapper mapper;

    @Mock
    private AlertReadService readService;

    @InjectMocks
    private AlertEventService service;

    private AlertEventEntity event(Long id, String status) {
        AlertEventEntity e = new AlertEventEntity();
        e.setId(id);
        e.setRuleId(1L);
        e.setRuleName("cpu-high");
        e.setService("svc-a");
        e.setMetric("cpu");
        e.setStatus(status);
        e.setFiredAt(LocalDateTime.now().minusMinutes(5));
        return e;
    }

    // ==================== acknowledge（确认自动认领） ====================

    @Test
    @DisplayName("acknowledge：FIRING 确认后变为 ACKNOWLEDGED 并记录认领人/时间")
    void acknowledge_firingBecomesAcknowledged() {
        AlertEventEntity e = event(1L, "FIRING");
        when(mapper.selectById(1L)).thenReturn(e);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            AlertEventEntity result = service.acknowledge(1L);

            assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
            assertThat(result.getAcknowledgedBy()).isEqualTo("admin");
            assertThat(result.getAcknowledgedAt()).isNotNull();
            verify(mapper).updateById(e);
        }
    }

    @Test
    @DisplayName("acknowledge：SILENCED 事件也可确认")
    void acknowledge_silencedBecomesAcknowledged() {
        AlertEventEntity e = event(2L, "SILENCED");
        when(mapper.selectById(2L)).thenReturn(e);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("operator");

            AlertEventEntity result = service.acknowledge(2L);

            assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
            assertThat(result.getAcknowledgedBy()).isEqualTo("operator");
        }
    }

    @Test
    @DisplayName("acknowledge：已恢复事件抛 ALERT_EVENT_NOT_FOUND")
    void acknowledge_resolvedThrows() {
        AlertEventEntity e = event(3L, "RESOLVED");
        when(mapper.selectById(3L)).thenReturn(e);

        assertThatThrownBy(() -> service.acknowledge(3L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_NOT_FOUND);
    }

    // ==================== claim（独立认领 / 接管） ====================

    @Test
    @DisplayName("claim：FIRING 独立认领后自动转 ACK 并记录 owner")
    void claim_firingBecomesAcknowledged() {
        AlertEventEntity e = event(4L, "FIRING");
        when(mapper.selectById(4L)).thenReturn(e);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            AlertEventEntity result = service.claim(4L);

            assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
            assertThat(result.getAcknowledgedBy()).isEqualTo("admin");
            assertThat(result.getAcknowledgedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("claim：SILENCED 独立认领后自动转 ACK")
    void claim_silencedBecomesAcknowledged() {
        AlertEventEntity e = event(5L, "SILENCED");
        when(mapper.selectById(5L)).thenReturn(e);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            AlertEventEntity result = service.claim(5L);

            assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
        }
    }

    @Test
    @DisplayName("claim：接管已 ACK 事件可覆盖 owner（规格 3.4）")
    void claim_takeoverOverwritesOwner() {
        AlertEventEntity e = event(6L, "ACKNOWLEDGED");
        e.setAcknowledgedBy("old-owner");
        when(mapper.selectById(6L)).thenReturn(e);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("new-owner");

            AlertEventEntity result = service.claim(6L);

            assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
            assertThat(result.getAcknowledgedBy()).isEqualTo("new-owner");
        }
    }

    @Test
    @DisplayName("claim：已恢复事件抛 ALERT_EVENT_NOT_FOUND")
    void claim_resolvedThrows() {
        AlertEventEntity e = event(7L, "RESOLVED");
        when(mapper.selectById(7L)).thenReturn(e);

        assertThatThrownBy(() -> service.claim(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_NOT_FOUND);
    }

    // ==================== resolve（标记恢复） ====================

    @Test
    @DisplayName("resolve：恢复并记录恢复人/备注")
    void resolve_records() {
        AlertEventEntity e = event(8L, "ACKNOWLEDGED");
        when(mapper.selectById(8L)).thenReturn(e);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            AlertEventEntity result = service.resolve(8L, "已恢复");

            assertThat(result.getStatus()).isEqualTo("RESOLVED");
            assertThat(result.getResolvedBy()).isEqualTo("admin");
            assertThat(result.getResolutionNote()).isEqualTo("已恢复");
            assertThat(result.getResolvedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("resolve：备注超过 500 字截断")
    void resolve_truncatesNote() {
        AlertEventEntity e = event(9L, "FIRING");
        when(mapper.selectById(9L)).thenReturn(e);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            AlertEventEntity result = service.resolve(9L, "x".repeat(600));

            assertThat(result.getResolutionNote()).hasSize(500);
        }
    }

    @Test
    @DisplayName("resolve：已恢复事件抛 ALERT_EVENT_NOT_FOUND")
    void resolve_resolvedThrows() {
        AlertEventEntity e = event(10L, "RESOLVED");
        when(mapper.selectById(10L)).thenReturn(e);

        assertThatThrownBy(() -> service.resolve(10L, "n"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_NOT_FOUND);
    }

    // ==================== silence（静默，规格 2.5） ====================

    @Test
    @DisplayName("silence：静默成功并记录完整字段")
    void silence_success() {
        AlertEventEntity e = event(11L, "ACKNOWLEDGED");
        when(mapper.selectById(11L)).thenReturn(e);
        LocalDateTime until = LocalDateTime.now().plusHours(2);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            AlertEventEntity result = service.silence(11L, "PLANNED_MAINTENANCE", until, "例行维护");

            assertThat(result.getStatus()).isEqualTo("SILENCED");
            assertThat(result.getSilencedBy()).isEqualTo("admin");
            assertThat(result.getSilencedFrom()).isEqualTo("ACKNOWLEDGED");
            assertThat(result.getSilencedUntil()).isEqualTo(until);
            assertThat(result.getSilencedReason()).isEqualTo("PLANNED_MAINTENANCE");
            assertThat(result.getSilencedNote()).isEqualTo("例行维护");
            assertThat(result.getSilencedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("silence：重复静默不覆盖 silencedFrom")
    void silence_keepsOriginalFrom() {
        AlertEventEntity e = event(12L, "FIRING");
        e.setSilencedFrom("FIRING");
        when(mapper.selectById(12L)).thenReturn(e);

        AlertEventEntity result = service.silence(12L, "KNOWN_ISSUE",
                LocalDateTime.now().plusHours(1), null);

        assertThat(result.getSilencedFrom()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("silence：到期时间早于 15 分钟后抛 ALERT_EVENT_INVALID")
    void silence_tooSoonThrows() {
        AlertEventEntity e = event(13L, "FIRING");
        when(mapper.selectById(13L)).thenReturn(e);

        assertThatThrownBy(() -> service.silence(13L, "OTHER",
                LocalDateTime.now().plusMinutes(5), null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_INVALID);
    }

    @Test
    @DisplayName("silence：到期时间超过 7 天抛 ALERT_EVENT_INVALID")
    void silence_tooFarThrows() {
        AlertEventEntity e = event(14L, "FIRING");
        when(mapper.selectById(14L)).thenReturn(e);

        assertThatThrownBy(() -> service.silence(14L, "OTHER",
                LocalDateTime.now().plusDays(8), null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_INVALID);
    }

    @Test
    @DisplayName("silence：已恢复事件抛 ALERT_EVENT_NOT_FOUND")
    void silence_resolvedThrows() {
        AlertEventEntity e = event(15L, "RESOLVED");
        when(mapper.selectById(15L)).thenReturn(e);

        assertThatThrownBy(() -> service.silence(15L, "OTHER",
                LocalDateTime.now().plusHours(1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_NOT_FOUND);
    }

    // ==================== unsilence（解除静默，规格 2.2） ====================

    @Test
    @DisplayName("unsilence：SILENCED 解除后回静默前状态并清字段（保留 silencedAt 时间线）")
    void unsilence_restoresFrom() {
        AlertEventEntity e = event(16L, "SILENCED");
        e.setSilencedFrom("ACKNOWLEDGED");
        e.setSilencedAt(LocalDateTime.now());
        e.setSilencedUntil(LocalDateTime.now().plusHours(1));
        when(mapper.selectById(16L)).thenReturn(e);

        AlertEventEntity result = service.unsilence(16L);

        assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(result.getSilencedUntil()).isNull();
        assertThat(result.getSilencedReason()).isNull();
        assertThat(result.getSilencedBy()).isNull();
        assertThat(result.getSilencedNote()).isNull();
        assertThat(result.getSilencedFrom()).isNull();
        // 时间线保留：silencedAt 不清除
        assertThat(result.getSilencedAt()).isNotNull();
    }

    @Test
    @DisplayName("unsilence：silencedFrom 为空时回 FIRING")
    void unsilence_defaultFiring() {
        AlertEventEntity e = event(17L, "SILENCED");
        e.setSilencedFrom(null);
        when(mapper.selectById(17L)).thenReturn(e);

        AlertEventEntity result = service.unsilence(17L);

        assertThat(result.getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("unsilence：非静默状态抛 ALERT_EVENT_INVALID")
    void unsilence_notSilencedThrows() {
        AlertEventEntity e = event(18L, "FIRING");
        when(mapper.selectById(18L)).thenReturn(e);

        assertThatThrownBy(() -> service.unsilence(18L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_INVALID);
    }

    // ==================== batchAcknowledge / restoreExpiredSilences ====================

    @Test
    @DisplayName("batchAcknowledge：成功确认并返回条数")
    void batchAcknowledge_counts() {
        AlertEventEntity firing = event(19L, "FIRING");
        when(mapper.selectById(19L)).thenReturn(firing);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            int count = service.batchAcknowledge(List.of(19L));

            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("batchAcknowledge：跳过已恢复事件，返回成功条数")
    void batchAcknowledge_skipsResolved() {
        when(mapper.selectById(19L)).thenReturn(event(19L, "FIRING"));
        when(mapper.selectById(20L)).thenReturn(event(20L, "RESOLVED"));
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            int count = service.batchAcknowledge(List.of(19L, 20L));

            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("batchAcknowledge：空列表返回 0")
    void batchAcknowledge_empty() {
        assertThat(service.batchAcknowledge(List.of())).isZero();
    }

    @Test
    @DisplayName("restoreExpiredSilences：到期静默自动恢复")
    void restoreExpiredSilences_restores() {
        AlertEventEntity expired = event(21L, "SILENCED");
        expired.setSilencedFrom("FIRING");
        expired.setSilencedUntil(LocalDateTime.now().minusMinutes(1));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(expired));
        when(mapper.selectById(21L)).thenReturn(expired);

        service.restoreExpiredSilences();

        assertThat(expired.getStatus()).isEqualTo("FIRING");
        verify(mapper).updateById(expired);
    }

    @Test
    @DisplayName("restoreExpiredSilences：无到期事件不处理")
    void restoreExpiredSilences_empty() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.restoreExpiredSilences();

        verify(mapper, never()).updateById(any(AlertEventEntity.class));
    }

    // ==================== batchSilence（P1 批量静默 / 维护窗口） ====================

    @Test
    @DisplayName("batchSilence：批量静默成功并返回条数")
    void batchSilence_counts() {
        AlertEventEntity firing = event(22L, "FIRING");
        when(mapper.selectById(22L)).thenReturn(firing);
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            int count = service.batchSilence(List.of(22L), "PLANNED_MAINTENANCE",
                    LocalDateTime.now().plusHours(2), "维护窗口批量静默");

            assertThat(count).isEqualTo(1);
            assertThat(firing.getStatus()).isEqualTo("SILENCED");
        }
    }

    @Test
    @DisplayName("batchSilence：跳过已恢复等不可静默事件，返回成功条数")
    void batchSilence_skipsResolved() {
        when(mapper.selectById(22L)).thenReturn(event(22L, "FIRING"));
        when(mapper.selectById(23L)).thenReturn(event(23L, "RESOLVED"));
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            int count = service.batchSilence(List.of(22L, 23L), "OTHER",
                    LocalDateTime.now().plusHours(1), null);

            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("batchSilence：空列表返回 0")
    void batchSilence_empty() {
        assertThat(service.batchSilence(List.of(), "OTHER",
                LocalDateTime.now().plusHours(1), null)).isZero();
    }

    // ==================== toResponse（已读填充） ====================

    @Test
    @DisplayName("toResponse：填充当前用户有效已读标记")
    void toResponse_fillsRead() {
        AlertEventEntity e = event(1L, "FIRING");
        when(readService.readIds(any(), any(), any())).thenReturn(Set.of(1L));
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::getCurrentUsername).thenReturn("admin");

            AlertEventResponse response = service.toResponse(e);

            assertThat(response.getId()).isEqualTo(1L);
            assertThat(response.getRead()).isTrue();
        }
    }
}
