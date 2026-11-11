package com.example.opsaiagent.automation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.automation.dto.AgentTaskDetail;
import com.example.opsaiagent.automation.dto.AgentTaskSummary;
import com.example.opsaiagent.automation.entity.AgentEvidenceEntity;
import com.example.opsaiagent.automation.entity.AgentStepEntity;
import com.example.opsaiagent.automation.entity.AgentTaskEntity;
import com.example.opsaiagent.automation.mapper.AgentEvidenceMapper;
import com.example.opsaiagent.automation.mapper.AgentStepMapper;
import com.example.opsaiagent.automation.mapper.AgentTaskMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.inspection.service.InspectionService;
import com.example.opsaiagent.rbac.service.DataScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 每次查询都重建授权，不依赖任务创建时的内存身份。 */
@Service
@RequiredArgsConstructor
public class AgentTaskQueryService {
    private final AgentTaskMapper tasks;
    private final AgentStepMapper steps;
    private final AgentEvidenceMapper evidence;
    private final InspectionService inspection;
    private final DataScope scope;
    private final ObjectMapper mapper;

    /**
     * 读取当前可见任务的一致详情快照，步骤和证据均限定目标租户。
     * @param taskId 任务 ID
     * @return 不包含执行令牌的详情
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AgentTaskDetail detail(String taskId) {
        AgentTaskEntity task = tasks.selectOne(visible().eq(AgentTaskEntity::getId, taskId));
        if (task == null) throw new BusinessException(ErrorCode.AGENT_TASK_NOT_FOUND);
        var target = inspection.requireVisibleService(task.getServiceId());
        if (!task.getTenantId().equals(target.getTenantId())) {
            throw new BusinessException(ErrorCode.AGENT_TASK_NOT_FOUND);
        }
        List<AgentTaskDetail.Step> stepViews = steps.selectList(new LambdaQueryWrapper<AgentStepEntity>()
                        .eq(AgentStepEntity::getTenantId, task.getTenantId()).eq(AgentStepEntity::getTaskId, taskId)
                        .orderByAsc(AgentStepEntity::getStepNo).last("LIMIT 100"))
                .stream().map(step -> new AgentTaskDetail.Step(step.getId(), step.getStepNo(), step.getSkill(),
                        step.getStatus(), step.getErrorCode(), step.getStartedAt(), step.getCompletedAt())).toList();
        List<AgentTaskDetail.Evidence> evidenceViews = evidence.selectList(new LambdaQueryWrapper<AgentEvidenceEntity>()
                        .eq(AgentEvidenceEntity::getTenantId, task.getTenantId()).eq(AgentEvidenceEntity::getTaskId, taskId)
                        .orderByAsc(AgentEvidenceEntity::getObservedAt).orderByAsc(AgentEvidenceEntity::getId).last("LIMIT 100"))
                .stream().map(this::toEvidence).toList();
        return new AgentTaskDetail(task.getId(), task.getServiceId(), task.getTaskType(), task.getStatus(),
                task.getOutcome(), task.getRequestedBy(), task.getErrorCode(), task.getCreatedAt(),
                task.getCompletedAt(), stepViews, evidenceViews);
    }

    /**
     * 按当前可见服务列出最近任务，历史部门快照也需满足可见范围。
     * @param serviceId 已登记服务 ID
     * @param limit 数量上限，1～50
     * @return 最新优先的任务摘要
     */
    public List<AgentTaskSummary> list(String serviceId, int limit) {
        if (limit < 1 || limit > 50) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        var target = inspection.requireVisibleService(serviceId);
        return tasks.selectList(visible().eq(AgentTaskEntity::getTenantId, target.getTenantId())
                        .eq(AgentTaskEntity::getServiceId, serviceId).orderByDesc(AgentTaskEntity::getCreatedAt)
                        .orderByDesc(AgentTaskEntity::getId).last("LIMIT " + limit))
                .stream().map(task -> new AgentTaskSummary(task.getId(), task.getServiceId(), task.getStatus(),
                        task.getOutcome(), task.getRequestedBy(), task.getCreatedAt(), task.getCompletedAt())).toList();
    }

    /**
     * 构造可信租户与历史部门范围，缺少租户上下文时拒绝查询。
     * 无入参。
     * @return 数据范围过滤器
     */
    private LambdaQueryWrapper<AgentTaskEntity> visible() {
        String tenant = scope.tenantId();
        if (tenant == null || tenant.isBlank()) throw new BusinessException(ErrorCode.TENANT_REQUIRED);
        var query = new LambdaQueryWrapper<AgentTaskEntity>();
        if (!scope.isPlatformTenant()) {
            query.eq(AgentTaskEntity::getTenantId, tenant);
            if (scope.deptId() != null) {
                List<Long> departments = scope.visibleDeptIds();
                if (departments.isEmpty()) query.apply("1 = 0");
                else query.in(AgentTaskEntity::getDeptId, departments);
            }
        }
        return query;
    }

    /**
     * 还原已知类型的结构化报告，不将原始 JSON 字符串直接输出。
     * @param item 已持久化证据
     * @return 明确类型的证据视图
     */
    private AgentTaskDetail.Evidence toEvidence(AgentEvidenceEntity item) {
        try {
            if (!"INSPECTION_REPORT".equals(item.getEvidenceType())) {
                throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID);
            }
            return new AgentTaskDetail.Evidence(item.getId(), item.getStepId(), item.getEvidenceType(),
                    item.getObservedAt(), mapper.readValue(item.getPayload(), InspectionReport.class));
        } catch (JsonProcessingException invalidEvidence) {
            throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID, "任务证据格式无效");
        }
    }
}
