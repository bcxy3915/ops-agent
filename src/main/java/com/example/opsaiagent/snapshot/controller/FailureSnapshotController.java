package com.example.opsaiagent.snapshot.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.snapshot.dto.SnapshotData;
import com.example.opsaiagent.snapshot.entity.FailureSnapshotEntity;
import com.example.opsaiagent.snapshot.service.FailureSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 故障快照查看接口（T13）
 */
@Tag(name = "故障快照", description = "故障快照查询（告警触发时自动生成）")
@RestController
@RequestMapping("/api/snapshots")
@RequiredArgsConstructor
public class FailureSnapshotController {

    private final FailureSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "查询故障快照列表", description = "可按服务 / 告警事件过滤")
    @GetMapping
    public ApiResponse<List<FailureSnapshotEntity>> list(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Long alertEventId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(snapshotService.list(service, alertEventId, limit));
    }

    @Operation(summary = "查询单个故障快照", description = "返回结构化 JSON 快照")
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        FailureSnapshotEntity entity = snapshotService.getById(id);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("alertEventId", entity.getAlertEventId());
        result.put("ruleId", entity.getRuleId());
        result.put("ruleName", entity.getRuleName());
        result.put("service", entity.getService());
        result.put("metric", entity.getMetric());
        result.put("status", entity.getStatus());
        result.put("createdAt", entity.getCreatedAt());
        try {
            result.put("snapshot", objectMapper.readValue(entity.getSnapshotJson(), SnapshotData.class));
        } catch (Exception e) {
            result.put("snapshot", entity.getSnapshotJson());
        }
        return ApiResponse.success(result);
    }
}
