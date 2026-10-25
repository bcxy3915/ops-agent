package com.example.opsaiagent.oncall.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.oncall.dto.OncallMemberCreateRequest;
import com.example.opsaiagent.oncall.dto.OncallMemberResponse;
import com.example.opsaiagent.oncall.service.OncallMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 值班管理接口（P1 规格 7）
 * 监控运维组「值班管理」菜单：配置主值班 / 备值班（可多人），不做排班轮换。
 * 联动：首报→主值班、升级 L1→备值班、L2→ADMIN；规则显式 toUsers 优先；值班表为空回退角色兜底。
 */
@Tag(name = "值班管理", description = "全局值班人配置：主值班 / 备值班")
@RestController
@RequestMapping("/api/oncall/members")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('oncall:manage')")
public class OncallMemberController {

    private final OncallMemberService oncallService;

    @Operation(summary = "查询值班人列表", description = "可按角色 PRIMARY/BACKUP 筛选。所有角色可访问")
    @GetMapping
    public ApiResponse<List<OncallMemberResponse>> list(
            @RequestParam(required = false) String dutyRole) {
        return ApiResponse.success(oncallService.list(dutyRole));
    }

    @Operation(summary = "添加值班人", description = "同用户+角色幂等。需 oncall:manage")
    @AuditLog(operation = "ADD_ONCALL_MEMBER", description = "添加值班人")
    @PostMapping
    public ApiResponse<OncallMemberResponse> add(@Valid @RequestBody OncallMemberCreateRequest request) {
        return ApiResponse.success(oncallService.add(request));
    }

    @Operation(summary = "移除值班人", description = "需 oncall:manage")
    @AuditLog(operation = "REMOVE_ONCALL_MEMBER", description = "移除值班人")
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> remove(@PathVariable Long id) {
        oncallService.remove(id);
        return ApiResponse.success(Map.of("deleted", String.valueOf(id)));
    }
}
