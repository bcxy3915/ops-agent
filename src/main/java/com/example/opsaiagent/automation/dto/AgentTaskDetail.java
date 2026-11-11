package com.example.opsaiagent.automation.dto;

import com.example.opsaiagent.inspection.dto.InspectionReport;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务详情投影，不暴露执行令牌、目标地址或服务凭据。
 * @param id 任务 ID
 * @param serviceId 服务 ID
 * @param taskType 任务类型
 * @param status 执行状态，与观测健康结论分开
 * @param outcome 观测结论，未完成时为空
 * @param requestedBy 发起人
 * @param errorCode 固定错误码，失败时提供
 * @param createdAt 创建时间，UTC
 * @param completedAt 完成时间，UTC
 * @param steps 已记录步骤
 * @param evidence 已持久化证据
 */
public record AgentTaskDetail(String id, String serviceId, String taskType, String status, String outcome,
                               String requestedBy, String errorCode, LocalDateTime createdAt,
                               LocalDateTime completedAt, List<Step> steps, List<Evidence> evidence) {
    /**
     * 步骤视图。
     * @param id 步骤 ID
     * @param stepNo 顺序号
     * @param skill 固定技能名
     * @param status 状态
     * @param errorCode 固定错误码
     * @param startedAt 开始时间，UTC
     * @param completedAt 完成时间，UTC
     */
    public record Step(String id, int stepNo, String skill, String status, String errorCode,
                       LocalDateTime startedAt, LocalDateTime completedAt) {
    }

    /**
     * 首版实时巡检证据视图；后续工具以明确类型扩展。
     * @param id 证据 ID
     * @param stepId 归属步骤 ID
     * @param evidenceType 证据类型
     * @param observedAt 采集时间，UTC
     * @param report 原结构化巡检报告
     */
    public record Evidence(String id, String stepId, String evidenceType,
                           LocalDateTime observedAt, InspectionReport report) {
    }
}
