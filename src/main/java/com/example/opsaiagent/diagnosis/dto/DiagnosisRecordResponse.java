package com.example.opsaiagent.diagnosis.dto;

import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断记录响应（T16）
 * 展示结构化诊断结果（证据/建议解析为数组）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiagnosisRecordResponse {

    private Long id;
    private Long alertEventId;
    private Long snapshotId;
    private Long ruleId;
    private String ruleName;
    private String service;
    private String metric;
    private String status;
    private String rootCause;
    private List<String> evidence;
    private Integer confidence;
    private List<String> suggestions;
    private String summary;
    private String question;
    private String answer;
    private String errorMessage;
    private Long durationMs;
    private List<DiagnosisReferenceResponse> references;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DiagnosisRecordResponse from(DiagnosisRecordEntity entity, DiagnosisServiceView view) {
        return DiagnosisRecordResponse.builder()
                .id(entity.getId())
                .alertEventId(entity.getAlertEventId())
                .snapshotId(entity.getSnapshotId())
                .ruleId(entity.getRuleId())
                .ruleName(entity.getRuleName())
                .service(entity.getService())
                .metric(entity.getMetric())
                .status(entity.getStatus())
                .rootCause(entity.getRootCause())
                .evidence(view.evidence())
                .confidence(entity.getConfidence())
                .suggestions(view.suggestions())
                .summary(entity.getSummary())
                .question(entity.getQuestion())
                .answer(entity.getAnswer())
                .errorMessage(entity.getErrorMessage())
                .durationMs(entity.getDurationMs())
                .references(view.references())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public record DiagnosisServiceView(List<String> evidence, List<String> suggestions,
                                       List<DiagnosisReferenceResponse> references) {
    }
}
