package com.example.opsaiagent.automation.dto;

import java.time.LocalDateTime;

/**
 * 有界任务列表摘要。
 * @param id 任务 ID
 * @param serviceId 服务 ID
 * @param status 执行状态
 * @param outcome 观测结论
 * @param requestedBy 发起人
 * @param createdAt 创建时间，UTC
 * @param completedAt 完成时间，UTC
 */
public record AgentTaskSummary(String id, String serviceId, String status, String outcome,
                                String requestedBy, LocalDateTime createdAt, LocalDateTime completedAt) {
}
