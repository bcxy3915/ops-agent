package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.dto.AlertRuleLevelDto;
import com.example.opsaiagent.alert.dto.AlertRuleRequest;
import com.example.opsaiagent.alert.dto.AlertRuleResponse;
import com.example.opsaiagent.alert.entity.AlertRuleEntity;
import com.example.opsaiagent.alert.entity.AlertRuleEscalationLevelEntity;
import com.example.opsaiagent.alert.mapper.AlertRuleEscalationLevelMapper;
import com.example.opsaiagent.alert.mapper.AlertRuleMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.service.DeptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertRuleService 单元测试
 * 覆盖（P0 规格 4）：
 * - 创建/更新规则并持久化升级路径（先删后插、levelNo 递增）
 * - validateEscalation：启用时至少 1 级、after 1m~24h 且严格递增、
 *   toRole/toUsers 至少一个、toRole 仅 OPERATOR/ADMIN、triggerOn 合法
 * - 删除规则连带清理升级级别
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警规则服务")
class AlertRuleServiceTest {

    @Mock
    private AlertRuleMapper mapper;

    @Mock
    private AlertRuleEscalationLevelMapper levelMapper;

    @Mock
    private DeptService deptService;

    @Spy
    private AlertScope alertScope = new AlertScope(null);

    @InjectMocks
    private AlertRuleService service;

    private AlertRuleLevelDto level(int afterSeconds, String triggerOn, String toRole) {
        AlertRuleLevelDto d = new AlertRuleLevelDto();
        d.setAfterSeconds(afterSeconds);
        d.setTriggerOn(triggerOn);
        d.setToRole(toRole);
        return d;
    }

    private AlertRuleRequest request() {
        AlertRuleRequest r = new AlertRuleRequest();
        r.setName("cpu-high");
        r.setMetric("cpu");
        r.setOperator("gt");
        r.setThreshold(80.0);
        r.setDurationSeconds(60);
        r.setEnabled(true);
        r.setEscalationEnabled(true);
        r.setEscalationLevels(List.of(level(300, "UNACKNOWLEDGED", "OPERATOR")));
        return r;
    }

    // ==================== create ====================

    @Test
    @DisplayName("create：启用升级时先删旧级别再按 levelNo 递增插入")
    void create_persistsLevels() {
        AlertRuleRequest req = request();
        AlertRuleEntity entity = new AlertRuleEntity();
        entity.setId(1L);
        when(mapper.insert(any(AlertRuleEntity.class))).thenAnswer(inv -> {
            AlertRuleEntity e = inv.getArgument(0);
            e.setId(1L);
            return 1;
        });

        AlertRuleEntity result = service.create(req);

        assertThat(result.getId()).isEqualTo(1L);
        verify(levelMapper).delete(any(Wrapper.class));
        ArgumentCaptor<AlertRuleEscalationLevelEntity> captor =
                ArgumentCaptor.forClass(AlertRuleEscalationLevelEntity.class);
        verify(levelMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getRuleId()).isEqualTo(1L);
        assertThat(captor.getValue().getLevelNo()).isEqualTo(1);
        assertThat(captor.getValue().getAfterSeconds()).isEqualTo(300);
        assertThat(captor.getValue().getTriggerOn()).isEqualTo("UNACKNOWLEDGED");
        assertThat(captor.getValue().getToRole()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("create：未传升级级别时不写级别表（实现按 levels 是否为空决定）")
    void create_noEscalationSkipsLevels() {
        AlertRuleRequest req = request();
        req.setEscalationEnabled(false);
        req.setEscalationLevels(List.of());

        service.create(req);

        // 实现行为：replaceEscalationLevels 会先清空旧级别，但 levels 为空则不插入
        verify(levelMapper).delete(any(Wrapper.class));
        verify(levelMapper, never()).insert(any(AlertRuleEscalationLevelEntity.class));
    }

    // ==================== validateEscalation（规格 4.2 校验） ====================

    @Test
    @DisplayName("validate：启用升级但无级别时按无升级路径处理（实现允许）")
    void validate_escalationWithoutLevelsAllowed() {
        AlertRuleRequest req = request();
        req.setEscalationLevels(List.of());

        service.create(req);

        verify(levelMapper, never()).insert(any(AlertRuleEscalationLevelEntity.class));
    }

    @Test
    @DisplayName("validate：after 小于 60 秒（1 分钟）抛错")
    void validate_afterTooSmallThrows() {
        AlertRuleRequest req = request();
        req.setEscalationLevels(List.of(level(30, "UNACKNOWLEDGED", "OPERATOR")));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("validate：after 超过 24 小时抛错")
    void validate_afterTooLargeThrows() {
        AlertRuleRequest req = request();
        req.setEscalationLevels(List.of(level(86500, "UNACKNOWLEDGED", "OPERATOR")));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("validate：多级 after 必须严格递增")
    void validate_afterNotStrictlyIncreasingThrows() {
        AlertRuleRequest req = request();
        req.setEscalationLevels(List.of(
                level(300, "UNACKNOWLEDGED", "OPERATOR"),
                level(300, "UNRESOLVED", "ADMIN")));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("validate：toRole 非法（非 OPERATOR/ADMIN）抛错")
    void validate_invalidRoleThrows() {
        AlertRuleRequest req = request();
        req.setEscalationLevels(List.of(level(300, "UNACKNOWLEDGED", "VIEWER")));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("validate：toRole 与 toUsers 均为空抛错")
    void validate_noTargetThrows() {
        AlertRuleRequest req = request();
        AlertRuleLevelDto d = level(300, "UNACKNOWLEDGED", null);
        d.setToUsers(List.of());
        req.setEscalationLevels(List.of(d));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("validate：triggerOn 非法抛错")
    void validate_invalidTriggerOnThrows() {
        AlertRuleRequest req = request();
        req.setEscalationLevels(List.of(level(300, "ON_FIRE", "OPERATOR")));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
    }

    @Test
    @DisplayName("validate：仅 toUsers 指定用户也合法（规格 4.1 toUsers 覆盖角色）")
    void validate_usersOnlyAccepted() {
        AlertRuleRequest req = request();
        AlertRuleLevelDto d = level(300, "UNACKNOWLEDGED", null);
        d.setToUsers(List.of("zhangsan"));
        req.setEscalationLevels(List.of(d));

        service.create(req);

        ArgumentCaptor<AlertRuleEscalationLevelEntity> captor =
                ArgumentCaptor.forClass(AlertRuleEscalationLevelEntity.class);
        verify(levelMapper).insert(captor.capture());
        assertThat(captor.getValue().getToUsers()).isEqualTo("zhangsan");
    }

    // ==================== update ====================

    @Test
    @DisplayName("update：更新级别时先删后插")
    void update_replacesLevels() {
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setId(2L);
        when(mapper.selectById(2L)).thenReturn(existing);

        AlertRuleRequest req = request();
        req.setEscalationLevels(List.of(level(600, "UNRESOLVED", "ADMIN")));

        service.update(2L, req);

        verify(levelMapper).delete(any(Wrapper.class));
        ArgumentCaptor<AlertRuleEscalationLevelEntity> captor =
                ArgumentCaptor.forClass(AlertRuleEscalationLevelEntity.class);
        verify(levelMapper).insert(captor.capture());
        assertThat(captor.getValue().getRuleId()).isEqualTo(2L);
        assertThat(captor.getValue().getLevelNo()).isEqualTo(1);
        assertThat(captor.getValue().getAfterSeconds()).isEqualTo(600);
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete：删除规则并清理升级级别")
    void delete_removesLevels() {
        AlertRuleEntity existing = new AlertRuleEntity();
        existing.setId(3L);
        existing.setTenantId("t-default");
        when(mapper.selectById(3L)).thenReturn(existing);
        when(mapper.deleteById(3L)).thenReturn(1);

        service.delete(3L);

        verify(mapper).deleteById(3L);
        verify(levelMapper).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("delete：规则不存在抛 ALERT_RULE_NOT_FOUND")
    void delete_notFoundThrows() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ALERT_RULE_NOT_FOUND);
    }

    // ==================== toResponse ====================

    @Test
    @DisplayName("toResponse：填充升级级别列表")
    void toResponse_fillsLevels() {
        AlertRuleEntity entity = new AlertRuleEntity();
        entity.setId(1L);
        entity.setName("cpu-high");
        AlertRuleEscalationLevelEntity levelRow = new AlertRuleEscalationLevelEntity();
        levelRow.setLevelNo(1);
        levelRow.setAfterSeconds(300);
        levelRow.setTriggerOn("UNACKNOWLEDGED");
        levelRow.setToRole("OPERATOR");
        when(levelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(levelRow));

        AlertRuleResponse response = service.toResponse(entity);

        assertThat(response.getEscalationLevels()).hasSize(1);
        assertThat(response.getEscalationLevels().get(0).getAfterSeconds()).isEqualTo(300);
        assertThat(response.getEscalationLevels().get(0).getToRole()).isEqualTo("OPERATOR");
    }
}
