package com.example.opsaiagent.diagnosis.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.diagnosis.dto.DiagnosisFollowUpRequest;
import com.example.opsaiagent.diagnosis.dto.DiagnosisRecordResponse;
import com.example.opsaiagent.diagnosis.entity.DiagnosisRecordEntity;
import com.example.opsaiagent.diagnosis.service.DiagnosisService;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM 智能诊断接口（T16）
 * 诊断查询 + 手动触发诊断 + 追问。
 */
@Tag(name = "LLM 智能诊断", description = "基于故障快照的 LLM 根因诊断与追问")
@RestController
@RequestMapping("/api/diagnoses")
@RequiredArgsConstructor
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    @Operation(summary = "查询诊断记录", description = "可按服务 / 告警事件 / 规则过滤")
    @PreAuthorize("hasAuthority('diagnosis:read')")
    @GetMapping
    public ApiResponse<List<DiagnosisRecordResponse>> list(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Long alertEventId,
            @RequestParam(required = false) Long ruleId,
            @RequestParam(defaultValue = "50") int limit) {
        List<DiagnosisRecordResponse> list = diagnosisService.list(service, alertEventId, ruleId, limit)
                .stream().map(this::toResponse).toList();
        return ApiResponse.success(list);
    }

    @Operation(summary = "查询诊断详情", description = "返回结构化诊断结果")
    @PreAuthorize("hasAuthority('diagnosis:read')")
    @GetMapping("/{id}")
    public ApiResponse<DiagnosisRecordResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(toResponse(diagnosisService.getById(id)));
    }

    @Operation(summary = "手动触发诊断", description = "基于故障快照执行 LLM 诊断，需要 diagnosis:trigger")
    @RateLimit(key = "diagnosis", limit = 10, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('diagnosis:trigger')")
    @AuditLog(operation = "TRIGGER_DIAGNOSIS", description = "手动触发 LLM 诊断")
    @PostMapping("/snapshot/{snapshotId}")
    public ApiResponse<DiagnosisRecordResponse> diagnose(@PathVariable Long snapshotId) {
        return ApiResponse.success(toResponse(diagnosisService.diagnoseBySnapshot(snapshotId)));
    }

    @Operation(summary = "追问诊断", description = "基于已有诊断继续追问，需要 diagnosis:trigger")
    @RateLimit(key = "diagnosis", limit = 10, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('diagnosis:trigger')")
    @AuditLog(operation = "FOLLOW_UP_DIAGNOSIS", description = "诊断追问")
    @PostMapping("/{id}/follow-up")
    public ApiResponse<DiagnosisRecordResponse> followUp(@PathVariable Long id,
                                                         @Valid @RequestBody DiagnosisFollowUpRequest request) {
        return ApiResponse.success(toResponse(diagnosisService.followUp(id, request.getQuestion())));
    }

    private DiagnosisRecordResponse toResponse(DiagnosisRecordEntity entity) {
        return DiagnosisRecordResponse.from(entity, new DiagnosisRecordResponse.DiagnosisServiceView(
                diagnosisService.parseEvidence(entity.getEvidence()),
                diagnosisService.parseSuggestions(entity.getSuggestions()),
                diagnosisService.parseReferences(entity.getReferencesJson())));
    }
}
