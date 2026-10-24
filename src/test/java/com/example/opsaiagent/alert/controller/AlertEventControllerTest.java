package com.example.opsaiagent.alert.controller;

import com.example.opsaiagent.alert.dto.AlertEventResponse;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.service.AlertEventService;
import com.example.opsaiagent.common.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertEventController 单元测试
 * 覆盖：列表查询 / 确认事件
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警事件接口")
class AlertEventControllerTest {

    @Mock
    private AlertEventService alertEventService;

    @InjectMocks
    private AlertEventController controller;

    private AlertEventEntity entity() {
        AlertEventEntity e = new AlertEventEntity();
        e.setId(1L);
        e.setRuleId(1L);
        e.setRuleName("cpu-high");
        e.setStatus("FIRING");
        return e;
    }

    private AlertEventResponse response() {
        return AlertEventResponse.builder()
                .id(1L)
                .ruleId(1L)
                .ruleName("cpu-high")
                .status("FIRING")
                .build();
    }

    @Test
    @DisplayName("list：返回事件列表并透传过滤参数")
    void list_returnsEvents() {
        when(alertEventService.list("svc-a", "FIRING", 1L, 30)).thenReturn(List.of(entity()));
        when(alertEventService.toResponse(entity())).thenReturn(response());

        ApiResponse<List<AlertEventResponse>> result = controller.list("svc-a", "FIRING", 1L, 30);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getStatus()).isEqualTo("FIRING");
    }

    @Test
    @DisplayName("acknowledge：确认事件")
    void acknowledge_acknowledges() {
        AlertEventEntity acked = entity();
        acked.setStatus("ACKNOWLEDGED");
        AlertEventResponse ackedResp = AlertEventResponse.builder()
                .id(1L).ruleName("cpu-high").status("ACKNOWLEDGED").build();
        when(alertEventService.acknowledge(1L)).thenReturn(acked);
        when(alertEventService.toResponse(acked)).thenReturn(ackedResp);

        ApiResponse<AlertEventResponse> result = controller.acknowledge(1L);

        assertThat(result.getData().getStatus()).isEqualTo("ACKNOWLEDGED");
        verify(alertEventService).acknowledge(1L);
    }
}
