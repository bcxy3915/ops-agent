package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 故障案例详情（T20）
 * 五步闭环链路 + AI 判断根因（含置信度/证据）+ 处置预览 + 脱敏状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCaseDetailResponse {

    private Long id;
    private String tenantId;
    private Long deptId;
    private String visibility;
    private Long sourceAlertId;
    private Long diagnosisRecordId;
    private String service;
    private String title;
    private String symptom;
    private String rootCause;
    private String realRootCause;
    private List<String> evidence;
    private String effectiveAction;
    private Integer confidence;
    private String maskedStatus;
    private String status;
    private Integer durationMin;
    private String confirmedBy;
    private Integer citedCount;
    private LocalDateTime closedLoopAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 五步闭环链路 */
    private List<CaseFlowStep> flow;
}
