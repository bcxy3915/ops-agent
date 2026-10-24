package com.example.opsaiagent.alert.controller;

import com.example.opsaiagent.alert.dto.AlertRuleRequest;
import com.example.opsaiagent.alert.dto.AlertRuleResponse;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.service.AlertRuleService;
import com.example.opsaiagent.common.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertRuleController 单元测试
 * 覆盖：列表 / 创建 / 更新 / 启停 / 删除
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警规则接口")
class AlertRuleControllerTest {

    @Mock
    private AlertRuleService alertRuleService;

    @InjectMocks
    private AlertRuleController controller;

    private AlertRuleEntity entity() {
        AlertRuleEntity e = new AlertRuleEntity();
        e.setId(1L);
        e.setName("cpu-high");
        e.setMetric("cpu");
        e.setOperator("gt");
        e.setThreshold(80.0);
        e.setEnabled(true);
        return e;
    }

    private AlertRuleResponse response() {
        return AlertRuleResponse.builder()
                .id(1L)
                .name("cpu-high")
                .metric("cpu")
                .operator("gt")
                .threshold(80.0)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("list：返回规则列表")
    void list_returnsRules() {
        when(alertRuleService.list("svc-a", true)).thenReturn(List.of(entity()));
        when(alertRuleService.toResponse(entity())).thenReturn(response());

        ApiResponse<List<AlertRuleResponse>> result = controller.list("svc-a", true);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getName()).isEqualTo("cpu-high");
    }

    @Test
    @DisplayName("create：创建规则")
    void create_createsRule() {
        AlertRuleRequest request = new AlertRuleRequest();
        request.setName("cpu-high");
        when(alertRuleService.create(request)).thenReturn(entity());
        when(alertRuleService.toResponse(entity())).thenReturn(response());

        ApiResponse<AlertRuleResponse> result = controller.create(request);

        assertThat(result.getData().getId()).isEqualTo(1L);
        verify(alertRuleService).create(request);
    }

    @Test
    @DisplayName("update：更新规则")
    void update_updatesRule() {
        AlertRuleRequest request = new AlertRuleRequest();
        request.setName("cpu-high");
        when(alertRuleService.update(1L, request)).thenReturn(entity());
        when(alertRuleService.toResponse(entity())).thenReturn(response());

        ApiResponse<AlertRuleResponse> result = controller.update(1L, request);

        assertThat(result.getData().getThreshold()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("setEnabled：传 false 时停用规则")
    void setEnabled_disables() {
        AlertRuleEntity disabled = entity();
        disabled.setEnabled(false);
        when(alertRuleService.setEnabled(1L, false)).thenReturn(disabled);
        when(alertRuleService.toResponse(disabled)).thenReturn(
                AlertRuleResponse.builder().id(1L).enabled(false).build());

        ApiResponse<AlertRuleResponse> result = controller.setEnabled(1L, Map.of("enabled", false));

        assertThat(result.getData().getEnabled()).isFalse();
    }

    @Test
    @DisplayName("setEnabled：默认启用")
    void setEnabled_defaultsTrue() {
        when(alertRuleService.setEnabled(1L, true)).thenReturn(entity());
        when(alertRuleService.toResponse(entity())).thenReturn(response());

        ApiResponse<AlertRuleResponse> result = controller.setEnabled(1L, null);

        assertThat(result.getData().getEnabled()).isTrue();
    }

    @Test
    @DisplayName("delete：删除规则")
    void delete_deletesRule() {
        ApiResponse<Map<String, Object>> result = controller.delete(1L);

        assertThat(result.getData()).containsEntry("deleted", 1L);
        verify(alertRuleService).delete(1L);
    }
}
