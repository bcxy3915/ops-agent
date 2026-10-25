package com.example.opsaiagent.audit.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.audit.dto.AuditLogResponse;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.enums.AuditOperation;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import com.example.opsaiagent.audit.service.AuditQueryScope;
import com.example.opsaiagent.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "审计日志", description = "操作审计查询")
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('audit:read')")
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;
    private final AuditQueryScope auditQueryScope;

    @Operation(summary = "查询审计日志", description = "返回操作中文名（operationName），需 audit:read")
    @GetMapping
    public ApiResponse<IPage<AuditLogResponse>> list(            @Parameter(description = "用户名") @RequestParam(required = false) String username,
            @Parameter(description = "操作类型") @RequestParam(required = false) String operation,
            @Parameter(description = "结果") @RequestParam(required = false) String result,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int size) {

        LambdaQueryWrapper<AuditLogEntity> wrapper = auditQueryScope.current().logs();
        if (username != null) wrapper.eq(AuditLogEntity::getUsername, username);
        if (operation != null) wrapper.eq(AuditLogEntity::getOperation, operation);
        if (result != null) wrapper.eq(AuditLogEntity::getResult, result);
        wrapper.orderByDesc(AuditLogEntity::getCreatedAt);

        Page<AuditLogEntity> pageResult = auditLogMapper.selectPage(new Page<>(page, size), wrapper);
        IPage<AuditLogResponse> responsePage = pageResult.convert(this::toResponse);
        return ApiResponse.success(responsePage);
    }

    /**
     * 实体转响应，补充操作中文名
     */
    private AuditLogResponse toResponse(AuditLogEntity e) {
        return AuditLogResponse.builder()
                .id(e.getId())
                .username(e.getUsername())
                .tenantId(e.getTenantId())
                .deptId(e.getDeptId())
                .operation(e.getOperation())
                .operationName(AuditOperation.labelOf(e.getOperation()))
                .target(e.getTarget())
                .method(e.getMethod())
                .uri(e.getUri())
                .params(e.getParams())
                .result(e.getResult())
                .errorMessage(e.getErrorMessage())
                .ip(e.getIp())
                .userAgent(e.getUserAgent())
                .durationMs(e.getDurationMs())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
