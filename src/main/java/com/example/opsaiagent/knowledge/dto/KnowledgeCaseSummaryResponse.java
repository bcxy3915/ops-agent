package com.example.opsaiagent.knowledge.dto;

import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 故障案例列表项（T19）
 * 用于"待确认候选 / 已沉淀案例"列表 Tab。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCaseSummaryResponse {

    private Long id;
    private Long sourceAlertId;
    private Long diagnosisRecordId;
    private String service;
    private String title;
    private Integer confidence;
    private String status;
    private String maskedStatus;
    private Integer durationMin;
    private String confirmedBy;
    private LocalDateTime closedLoopAt;
    private LocalDateTime createdAt;
}
