package com.example.opsaiagent.automation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.opsaiagent.automation.entity.AgentEvidenceEntity;
import com.example.opsaiagent.automation.entity.AgentStepEntity;
import com.example.opsaiagent.automation.entity.AgentTaskEntity;
import com.example.opsaiagent.automation.diagnosis.AutonomousDiagnosisReport;
import com.example.opsaiagent.automation.mapper.AgentEvidenceMapper;
import com.example.opsaiagent.automation.mapper.AgentStepMapper;
import com.example.opsaiagent.automation.mapper.AgentTaskMapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 只负责短事务，采集和模型调用不得置于本服务事务内。 */
@Service
@RequiredArgsConstructor
public class InspectionTaskPersistence {
    private final AgentTaskMapper tasks;
    private final AgentStepMapper steps;
    private final AgentEvidenceMapper evidence;
    private final ObjectMapper mapper;

    /**
     * 原子创建任务与首个实时观测步骤。
     * @param service 已授权服务
     * @param requesterTenant 发起人的可信租户
     * @param username 可信登录名
     * @return 仅供服务端执行使用的任务，包含随机执行令牌
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskEntity begin(OpsServiceEntity service, String requesterTenant, String username) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(id());
        task.setTenantId(service.getTenantId());
        task.setDeptId(service.getDeptId());
        task.setServiceId(service.getId());
        task.setRequestTenantId(requesterTenant);
        task.setRequestedBy(username);
        task.setTaskType("INSPECTION");
        task.setStatus("RUNNING");
        task.setExecutionToken(id());
        task.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        requireOne(tasks.insert(task));
        AgentStepEntity step = new AgentStepEntity();
        step.setId(id());
        step.setTenantId(task.getTenantId());
        step.setTaskId(task.getId());
        step.setStepNo(1);
        step.setSkill("INSPECT_SERVICE");
        step.setStatus("RUNNING");
        step.setStartedAt(task.getCreatedAt());
        requireOne(steps.insert(step));
        return task;
    }

    /**
     * 原子创建独立诊断任务与初始巡检步骤。
     * @param service 已授权服务
     * @param requesterTenant 可信发起租户
     * @param username 可信用户名
     * @return 当前诊断任务
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskEntity beginDiagnosis(OpsServiceEntity service, String requesterTenant, String username) {
        var task = begin(service, requesterTenant, username);
        requireOne(tasks.update(null, new LambdaUpdateWrapper<AgentTaskEntity>()
                .eq(AgentTaskEntity::getId, task.getId()).eq(AgentTaskEntity::getTenantId, task.getTenantId())
                .set(AgentTaskEntity::getTaskType, "DIAGNOSIS")));
        task.setTaskType("DIAGNOSIS");
        return task;
    }

    /**
     * 原子保存初始巡检、实际取证步骤和完整诊断证据，人工接管不冒充成功。
     * @param task 本轮诊断任务
     * @param baseline 实际巡检报告
     * @param diagnosis 实际有界循环结果
     * @return 无返回值
     * @throws JsonProcessingException 序列化失败，事务回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void completeDiagnosis(AgentTaskEntity task, InspectionReport baseline,
                                  AutonomousDiagnosisReport diagnosis) throws JsonProcessingException {
        complete(task, baseline);
        var first = steps.selectOne(new LambdaQueryWrapper<AgentStepEntity>()
                .eq(AgentStepEntity::getTenantId, task.getTenantId()).eq(AgentStepEntity::getTaskId, task.getId())
                .eq(AgentStepEntity::getStepNo, 1));
        if (first == null) throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID);
        for (var observed : diagnosis.steps()) {
            AgentStepEntity step = new AgentStepEntity();
            step.setId(id());
            step.setTenantId(task.getTenantId());
            step.setTaskId(task.getId());
            step.setStepNo(observed.number());
            step.setSkill(observed.action());
            step.setStatus(observed.status());
            // 工具时间使用证据的实际采集完成时间，不伪造持久化时刻为采集开始。
            var collected = diagnosis.evidence().stream().filter(item -> item.id().equals(observed.evidenceId()))
                    .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID));
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(collected.collectedAt()), ZoneOffset.UTC);
            step.setStartedAt(time);
            step.setCompletedAt(time);
            requireOne(steps.insert(step));
        }
        AgentEvidenceEntity item = new AgentEvidenceEntity();
        item.setId(id());
        item.setTenantId(task.getTenantId());
        item.setTaskId(task.getId());
        item.setStepId(first.getId());
        item.setEvidenceType("DIAGNOSIS_REPORT");
        item.setPayload(mapper.writeValueAsString(diagnosis));
        item.setObservedAt(LocalDateTime.now(ZoneOffset.UTC));
        requireOne(evidence.insert(item));
        if ("NEEDS_MANUAL".equals(diagnosis.status())) {
            requireOne(tasks.update(null, new LambdaUpdateWrapper<AgentTaskEntity>()
                    .eq(AgentTaskEntity::getId, task.getId()).eq(AgentTaskEntity::getTenantId, task.getTenantId())
                    .eq(AgentTaskEntity::getExecutionToken, task.getExecutionToken()).eq(AgentTaskEntity::getStatus, "SUCCEEDED")
                    .set(AgentTaskEntity::getStatus, "NEEDS_MANUAL").set(AgentTaskEntity::getErrorCode, diagnosis.reason())));
        }
    }

    /**
     * 原子记录证据并结束任务，条件更新防止晚到回调重复写入。
     * @param task 当前执行任务
     * @param report 实际采集报告
     * @return 无返回值
     * @throws JsonProcessingException 结构化证据序列化失败，事务回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(AgentTaskEntity task, InspectionReport report) throws JsonProcessingException {
        if (!task.getServiceId().equals(report.serviceId())) {
            throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID);
        }
        String payload = mapper.writeValueAsString(report);
        LocalDateTime completed = LocalDateTime.now(ZoneOffset.UTC);
        transition(task, "SUCCEEDED", report.status(), null, completed);
        AgentStepEntity step = steps.selectOne(new LambdaQueryWrapper<AgentStepEntity>()
                .eq(AgentStepEntity::getTenantId, task.getTenantId())
                .eq(AgentStepEntity::getTaskId, task.getId()).eq(AgentStepEntity::getStepNo, 1));
        if (step == null) throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID);
        AgentEvidenceEntity item = new AgentEvidenceEntity();
        item.setId(id());
        item.setTenantId(task.getTenantId());
        item.setTaskId(task.getId());
        item.setStepId(step.getId());
        item.setEvidenceType("INSPECTION_REPORT");
        item.setPayload(payload);
        item.setObservedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(report.completedAt()), ZoneOffset.UTC));
        requireOne(evidence.insert(item));
        finishStep(task, "SUCCEEDED", null, completed);
    }

    /**
     * 将本轮任务与步骤原子标为失败，不保存原始异常中的地址或凭据。
     * @param task 本轮执行任务
     * @return 无返回值
     */
    @Transactional(rollbackFor = Exception.class)
    public void fail(AgentTaskEntity task) {
        LocalDateTime completed = LocalDateTime.now(ZoneOffset.UTC);
        transition(task, "FAILED", null, "INSPECTION_EXECUTION_FAILED", completed);
        finishStep(task, "FAILED", "INSPECTION_EXECUTION_FAILED", completed);
    }

    /**
     * 在租户、状态和执行令牌均匹配时结束任务。
     * @param task 当前执行身份
     * @param status 终态
     * @param outcome 观测结论，可为空
     * @param error 固定错误码，可为空
     * @param completed 完成时间
     * @return 无返回值
     */
    private void transition(AgentTaskEntity task, String status, String outcome, String error, LocalDateTime completed) {
        requireOne(tasks.update(null, new LambdaUpdateWrapper<AgentTaskEntity>()
                .eq(AgentTaskEntity::getId, task.getId()).eq(AgentTaskEntity::getTenantId, task.getTenantId())
                .eq(AgentTaskEntity::getExecutionToken, task.getExecutionToken()).eq(AgentTaskEntity::getStatus, "RUNNING")
                .set(AgentTaskEntity::getStatus, status).set(AgentTaskEntity::getOutcome, outcome)
                .set(AgentTaskEntity::getErrorCode, error).set(AgentTaskEntity::getCompletedAt, completed)));
    }

    /**
     * 同步结束唯一的巡检步骤。
     * @param task 本轮任务
     * @param status 步骤终态
     * @param error 固定错误码
     * @param completed 完成时间
     * @return 无返回值
     */
    private void finishStep(AgentTaskEntity task, String status, String error, LocalDateTime completed) {
        requireOne(steps.update(null, new LambdaUpdateWrapper<AgentStepEntity>()
                .eq(AgentStepEntity::getTenantId, task.getTenantId()).eq(AgentStepEntity::getTaskId, task.getId())
                .eq(AgentStepEntity::getStepNo, 1).eq(AgentStepEntity::getStatus, "RUNNING")
                .set(AgentStepEntity::getStatus, status).set(AgentStepEntity::getErrorCode, error)
                .set(AgentStepEntity::getCompletedAt, completed)));
    }

    /**
     * 拒绝缺失或重复状态写入。
     * @param rows 受影响行数
     * @return 无返回值
     */
    private void requireOne(int rows) {
        if (rows != 1) throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID);
    }

    /**
     * 生成随机记录标识或执行令牌。
     * 无入参。
     * @return 32 位随机标识
     */
    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
