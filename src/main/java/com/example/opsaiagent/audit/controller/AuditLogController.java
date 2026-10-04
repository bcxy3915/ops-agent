package com.example.opsaiagent.audit.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import com.example.opsaiagent.dto.ApiResponse;
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
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    @Operation(summary = "查询审计日志", description = "仅 ADMIN 可访问")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ApiResponse<Page<AuditLogEntity>> list(
            @Parameter(description = "用户名") @RequestParam(required = false) String username,
            @Parameter(description = "操作类型") @RequestParam(required = false) String operation,
            @Parameter(description = "结果") @RequestParam(required = false) String result,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") int size) {

        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        if (username != null) wrapper.eq(AuditLogEntity::getUsername, username);
        if (operation != null) wrapper.eq(AuditLogEntity::getOperation, operation);
        if (result != null) wrapper.eq(AuditLogEntity::getResult, result);
        wrapper.orderByDesc(AuditLogEntity::getCreatedAt);

        Page<AuditLogEntity> pageResult = auditLogMapper.selectPage(new Page<>(page, size), wrapper);
        return ApiResponse.success(pageResult);
    }
}