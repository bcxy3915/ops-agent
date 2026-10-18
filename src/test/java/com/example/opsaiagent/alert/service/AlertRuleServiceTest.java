package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.dto.AlertRuleRequest;
import com.example.opsaiagent.alert.dto.AlertRuleResponse;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.mapper.AlertRuleMapper;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertRuleService 单元测试
 * 覆盖：创建成功 / 重名 / 参数校验 / 更新 / 启停 / 删除 / 查询 / 不存在
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警规则服务")
class AlertRuleServiceTest {

    @Mock
    private AlertRuleMapper mapper;

    @InjectMocks
    private AlertRuleService service;

    private AlertRuleRequest request(String name, String operator) {
        AlertRuleRequest req = new AlertRuleRequest();
        req.setName(name);
        req.setService("svc-a");
        req.setMetric("cpu");
        req.setOperator(operator);
        req.setThreshold(80.0);
        req.setCompareMode("THRESHOLD");
        req.setDurationSeconds(60);
        req.setSilenceSeconds(0);
        req.setEnabled(true);
        return req;
    }

    private AlertRuleEntity entity(Long id) {
        AlertRuleEntity e = new AlertRuleEntity();
        e.setId(id);
        e.setName("rule-" + id);
        e.setMetric("cpu");
        e.setOperator("gt");
        e.setThreshold(80.0);
        e.setEnabled(true);
        return e;
    }

    // ==================== create ====================

    @Test
    @DisplayName("create：创建成功")
    void create_success() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        AlertRuleEntity created = service.create(request("cpu-high", "gt"));

        assertThat(created.getName()).isEqualTo("cpu-high");
        assertThat(created.getEnabled()).isTrue();
        verify(mapper).insert(any(AlertRuleEntity.class));
    }

    @Test
    @DisplayName("create：名称重复抛 ALERT_RULE_NAME_EXISTS")
    void create_duplicateName() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request("cpu-high", "gt")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_NAME_EXISTS);
    }

    @Test
    @DisplayName("create：非法比较符抛 ALERT_RULE_INVALID")
    void create_invalidOperator() {
        assertThatThrownBy(() -> service.create(request("cpu-high", "eq")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("create：非法比较模式抛 ALERT_RULE_INVALID")
    void create_invalidCompareMode() {
        AlertRuleRequest req = request("cpu-high", "gt");
        req.setCompareMode("UNKNOWN");
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("create：负持续时长抛 ALERT_RULE_INVALID")
    void create_negativeDuration() {
        AlertRuleRequest req = request("cpu-high", "gt");
        req.setDurationSeconds(-1);
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    // ==================== update ====================

    @Test
    @DisplayName("update：更新成功")
    void update_success() {
        AlertRuleEntity existing = entity(1L);
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        AlertRuleRequest req = request("cpu-high", "gt");
        req.setThreshold(90.0);
        AlertRuleEntity updated = service.update(1L, req);

        assertThat(updated.getThreshold()).isEqualTo(90.0);
        verify(mapper).updateById(existing);
    }

    @Test
    @DisplayName("update：规则不存在抛 ALERT_RULE_NOT_FOUND")
    void update_notFound() {
        when(mapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(1L, request("cpu-high", "gt")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_NOT_FOUND);
    }

    @Test
    @DisplayName("update：改名为其他规则名称抛 ALERT_RULE_NAME_EXISTS")
    void update_duplicateName() {
        AlertRuleEntity existing = entity(1L);
        existing.setName("old-name");
        when(mapper.selectById(1L)).thenReturn(existing);
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        AlertRuleRequest req = request("new-name", "gt");
        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_NAME_EXISTS);
    }

    // ==================== setEnabled / delete ====================

    @Test
    @DisplayName("setEnabled：切换启用状态")
    void setEnabled_toggles() {
        AlertRuleEntity existing = entity(1L);
        when(mapper.selectById(1L)).thenReturn(existing);

        AlertRuleEntity result = service.setEnabled(1L, false);

        assertThat(result.getEnabled()).isFalse();
        verify(mapper).updateById(existing);
    }

    @Test
    @DisplayName("delete：删除成功")
    void delete_success() {
        when(mapper.deleteById(1L)).thenReturn(1);

        service.delete(1L);

        verify(mapper).deleteById(1L);
    }

    @Test
    @DisplayName("delete：规则不存在抛 ALERT_RULE_NOT_FOUND")
    void delete_notFound() {
        when(mapper.deleteById(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_NOT_FOUND);
    }

    // ==================== list / get / toResponse ====================

    @Test
    @DisplayName("list：返回规则列表")
    void list_returnsRules() {
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(entity(1L)));

        List<AlertRuleEntity> list = service.list(null, null);

        assertThat(list).hasSize(1);
    }

    @Test
    @DisplayName("getById：规则不存在抛 ALERT_RULE_NOT_FOUND")
    void getById_notFound() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.getById(9L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_NOT_FOUND);
    }

    @Test
    @DisplayName("toResponse：实体转响应 DTO")
    void toResponse_maps() {
        AlertRuleEntity e = entity(1L);
        AlertRuleResponse response = service.toResponse(e);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("rule-1");
        assertThat(response.getThreshold()).isEqualTo(80.0);
    }
}
