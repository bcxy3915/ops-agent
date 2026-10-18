package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.dto.AlertEventResponse;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertEventService 单元测试
 * 覆盖：列表查询 / 确认 / 已恢复事件不可确认 / 不存在 / 最新 FIRING 查询 / 创建更新 / 转 DTO
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警事件服务")
class AlertEventServiceTest {

    @Mock
    private AlertEventMapper mapper;

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
        return e;
    }

    @Test
    @DisplayName("list：返回事件列表")
    void list_returnsEvents() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(event(1L, "FIRING")));

        List<AlertEventEntity> list = service.list(null, null, null, 50);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("getById：事件不存在抛 ALERT_EVENT_NOT_FOUND")
    void getById_notFound() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.getById(9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_EVENT_NOT_FOUND);
    }

    @Test
    @DisplayName("acknowledge：FIRING 事件确认后变为 ACKNOWLEDGED")
    void acknowledge_firingBecomesAcknowledged() {
        AlertEventEntity e = event(1L, "FIRING");
        when(mapper.selectById(1L)).thenReturn(e);

        AlertEventEntity result = service.acknowledge(1L);

        assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
        verify(mapper).updateById(e);
    }

    @Test
    @DisplayName("acknowledge：SILENCED 事件也可确认")
    void acknowledge_silencedBecomesAcknowledged() {
        AlertEventEntity e = event(2L, "SILENCED");
        when(mapper.selectById(2L)).thenReturn(e);

        AlertEventEntity result = service.acknowledge(2L);

        assertThat(result.getStatus()).isEqualTo("ACKNOWLEDGED");
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

    @Test
    @DisplayName("findLatestFiring：有 FIRING 事件返回最新")
    void findLatestFiring_returnsLatest() {
        AlertEventEntity firing = event(5L, "FIRING");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(firing));

        AlertEventEntity result = service.findLatestFiring(1L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("findLatestFiring：无 FIRING 事件返回 null")
    void findLatestFiring_empty() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.findLatestFiring(1L)).isNull();
    }

    @Test
    @DisplayName("create：插入事件")
    void create_inserts() {
        AlertEventEntity e = event(7L, "FIRING");

        AlertEventEntity result = service.create(e);

        assertThat(result.getUpdatedAt()).isNotNull();
        verify(mapper).insert(e);
    }

    @Test
    @DisplayName("update：更新事件并刷新 updatedAt")
    void update_refreshesTimestamp() {
        AlertEventEntity e = event(8L, "RESOLVED");

        service.update(e);

        assertThat(e.getUpdatedAt()).isNotNull();
        verify(mapper).updateById(e);
    }

    @Test
    @DisplayName("toResponse：实体转响应 DTO")
    void toResponse_maps() {
        AlertEventEntity e = event(1L, "FIRING");

        AlertEventResponse response = service.toResponse(e);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getRuleName()).isEqualTo("cpu-high");
        assertThat(response.getStatus()).isEqualTo("FIRING");
    }
}
