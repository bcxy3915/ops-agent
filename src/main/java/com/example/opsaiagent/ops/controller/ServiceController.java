package com.example.opsaiagent.ops.controller;

import com.example.opsaiagent.audit.annotation.AuditLog;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.ops.dto.ServiceRegisterRequest;
import com.example.opsaiagent.ops.dto.ServiceResponse;
import com.example.opsaiagent.ops.dto.ServiceUpdateRequest;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.ops.service.OpsServiceManager;
import com.example.opsaiagent.ops.service.ServiceHealthChecker;
import com.example.opsaiagent.ratelimit.annotation.RateLimit;
import com.example.opsaiagent.rbac.service.DeptService;
import com.example.opsaiagent.security.util.SecurityUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务管理接口。
 *
 * 服务归属“租户 + 部门”：企业用户只能看到本租户、本部门及下级部门的服务；
 * 平台管理员可按 tenantId 查看指定企业。
 */
@Tag(name = "服务管理", description = "运维服务的注册、查询、更新、下线")
@Slf4j
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceController {

    private final OpsServiceManager serviceManager;
    private final ServiceHealthChecker healthChecker;
    private final ObjectMapper objectMapper;
    private final DeptService deptService;

    /**
     * 注册服务
     * @param request 服务注册请求
     * @return 注册结果
     */
    @Operation(summary = "注册服务", description = "注册一个新的被监控服务。需要 service:manage")
    @RateLimit(key = "serviceWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('service:manage')")
    @AuditLog(operation = "REGISTER_SERVICE", description = "注册服务")
    @PostMapping
    public ApiResponse<ServiceResponse> register(@Valid @RequestBody ServiceRegisterRequest request) {
        String tenantId = resolveCreateTenant(request.getTenantId());
        Long deptId = resolveCreateDept(tenantId, request.getDeptId());

        Optional<OpsServiceEntity> existing = hasTenantContext()
                ? serviceManager.findByName(tenantId, request.getName())
                : serviceManager.findByName(request.getName());
        if (existing.isPresent()) {
            throw new BusinessException(ErrorCode.SERVICE_ALREADY_EXISTS,
                    "服务已存在: " + request.getName());
        }
        OpsServiceEntity entity = convertToEntity(request, tenantId, deptId);
        serviceManager.save(entity);
        return ApiResponse.success(toResponse(entity));
    }

    /**
     * 列出服务（兼容旧调用，不带租户/部门参数）
     * @param env 环境
     * @param status 状态
     * @return 服务列表
     */
    public ApiResponse<List<ServiceResponse>> list(String env, String status) {
        return list(env, status, null, null);
    }

    /**
     * 列出服务
     * @param env 环境
     * @param status 状态
     * @param tenantId 平台视角下的租户筛选
     * @param deptId 部门筛选
     * @return 服务列表
     */
    @Operation(summary = "查询服务列表", description = "企业按租户+部门子树过滤；平台可按 tenantId 查看")
    @PreAuthorize("hasAuthority('service:read')")
    @GetMapping
    public ApiResponse<List<ServiceResponse>> list(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) Long deptId) {
        String current = SecurityUtils.getCurrentTenantId();
        List<OpsServiceEntity> list;
        if (current == null || current.isBlank()) {
            // 兼容旧 Token / 单租户调用
            if (env != null && !env.isEmpty()) {
                list = serviceManager.findByEnv(env);
            } else if (status != null && !status.isEmpty()) {
                list = serviceManager.findByStatus(status);
            } else {
                list = serviceManager.listAll();
            }
        } else if ("0".equals(current)) {
            String targetTenant = tenantId == null || tenantId.isBlank() ? null : tenantId;
            List<Long> deptIds = targetTenant != null && deptId != null
                    ? deptService.subtreeIds(targetTenant, deptId) : null;
            list = serviceManager.listScoped(targetTenant, deptIds, env, status);
        } else {
            if (SecurityUtils.hasRole("TENANT_ADMIN")) {
                // 企业管理员可见本企业全部服务
                list = serviceManager.listScoped(current, null, env, status);
            } else {
                Long currentDept = SecurityUtils.getCurrentDeptId();
                Long effectiveDept = deptId != null ? deptId : currentDept;
                List<Long> allowed = currentDept == null
                        ? List.of() : deptService.subtreeIds(current, currentDept);
                if (effectiveDept == null || !allowed.contains(effectiveDept)) {
                    // 部门用户没有部门、或试图查询父/兄弟部门时，不能退化为全企业可见
                    list = List.of();
                } else {
                    List<Long> deptIds = deptService.subtreeIds(current, effectiveDept);
                    list = serviceManager.listScoped(current, deptIds, env, status);
                }
            }
        }
        return ApiResponse.success(list.stream().map(this::toResponse).toList());
    }

    /**
     * 获取服务详情
     * @param name 服务名
     * @return 服务详情
     */
    @Operation(summary = "查询单个服务", description = "需要 service:read，部门用户仅可见本部门子树服务")
    @PreAuthorize("hasAuthority('service:read')")
    @GetMapping("/{name}")
    public ApiResponse<ServiceResponse> get(@PathVariable String name) {
        OpsServiceEntity entity = findAccessibleService(name);
        return ApiResponse.success(toResponse(entity));
    }

    /**
     * 更新服务
     * @param name 服务名
     * @param request 更新请求
     * @return 更新结果
     */
    @Operation(summary = "更新服务", description = "需要 service:manage")
    @RateLimit(key = "serviceWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('service:manage')")
    @AuditLog(operation = "UPDATE_SERVICE", description = "更新服务")
    @PutMapping("/{name}")
    public ApiResponse<ServiceResponse> update(
            @PathVariable String name,
            @Valid @RequestBody ServiceUpdateRequest request) {
        OpsServiceEntity entity = findAccessibleService(name);
        if (request.getBaseUrl() != null) entity.setBaseUrl(request.getBaseUrl());
        if (request.getOwner() != null) entity.setOwner(request.getOwner());
        if (request.getEnv() != null) entity.setEnv(request.getEnv());
        if (request.getTags() != null) entity.setTags(toJson(request.getTags()));
        if (request.getDeptId() != null) {
            deptService.requireDept(entity.getTenantId(), request.getDeptId());
            entity.setDeptId(request.getDeptId());
        }
        serviceManager.update(entity);
        return ApiResponse.success(toResponse(entity));
    }

    /**
     * 删除服务
     * @param name 服务名
     * @return 删除结果
     */
    @Operation(summary = "下线服务", description = "需要 service:manage")
    @RateLimit(key = "serviceWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('service:manage')")
    @AuditLog(operation = "DELETE_SERVICE", description = "下线服务")
    @DeleteMapping("/{name}")
    public ApiResponse<Map<String, String>> delete(@PathVariable String name) {
        findAccessibleService(name);
        if (!deleteService(name)) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND);
        }
        return ApiResponse.success(Map.of("deleted", name));
    }

    /**
     * 检查服务健康状态
     * @param name 服务名
     * @return 健康状态
     */
    @Operation(summary = "触发健康检查", description = "立即检查一次指定服务的健康状态。需要 service:manage")
    @RateLimit(key = "serviceWrite", limit = 20, period = 60, dimension = RateLimit.Dimension.USER)
    @PreAuthorize("hasAuthority('service:manage')")
    @PostMapping("/{name}/check")
    public ApiResponse<Map<String, Object>> check(@PathVariable String name) {
        findAccessibleService(name);
        String status = healthChecker.checkNow(name);
        return ApiResponse.success(Map.of(
                "name", name,
                "status", status,
                "checkedAt", LocalDateTime.now()
        ));
    }

    /**
     * 按当前租户查找服务；无租户上下文时保持旧全局行为。
     *
     * @param name 服务名
     * @return 服务
     */
    private Optional<OpsServiceEntity> findService(String name) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank() || "0".equals(tenantId)) {
            return serviceManager.findByName(name);
        }
        return serviceManager.findByName(tenantId, name);
    }

    /**
     * 查询当前用户有权访问的服务。
     *
     * @param name 服务名
     * @return 服务
     */
    private OpsServiceEntity findAccessibleService(String name) {
        OpsServiceEntity entity = findService(name)
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_NOT_FOUND,
                        "服务不存在: " + name));
        if (!canAccessService(entity)) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND, "服务不存在: " + name);
        }
        return entity;
    }

    /**
     * 判断当前用户是否可以访问指定服务。
     *
     * @param entity 服务
     * @return true=可访问
     */
    private boolean canAccessService(OpsServiceEntity entity) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank() || "0".equals(tenantId)) {
            return true;
        }
        if (!tenantId.equals(entity.getTenantId())) {
            return false;
        }
        if (SecurityUtils.hasRole("TENANT_ADMIN")) {
            return true;
        }
        Long currentDept = SecurityUtils.getCurrentDeptId();
        return currentDept != null && entity.getDeptId() != null
                && deptService.subtreeIds(tenantId, currentDept).contains(entity.getDeptId());
    }

    /**
     * 按当前租户删除服务；无租户上下文时保持旧全局行为。
     *
     * @param name 服务名
     * @return 是否删除
     */
    private boolean deleteService(String name) {
        String tenantId = SecurityUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank() || "0".equals(tenantId)) {
            return serviceManager.deleteByName(name);
        }
        return serviceManager.deleteByName(tenantId, name);
    }

    /**
     * 解析注册服务的租户。
     *
     * @param requested 请求租户
     * @return 租户 ID
     */
    private String resolveCreateTenant(String requested) {
        String current = SecurityUtils.getCurrentTenantId();
        if (current == null || current.isBlank()) {
            return requested == null || requested.isBlank() ? "t-default" : requested;
        }
        if ("0".equals(current)) {
            if (requested == null || requested.isBlank()) {
                throw new BusinessException(ErrorCode.TENANT_REQUIRED, "平台注册服务必须指定 tenantId");
            }
            return requested;
        }
        if (requested != null && !requested.isBlank() && !requested.equals(current)) {
            throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能跨租户注册服务");
        }
        return current;
    }

    /**
     * 解析注册服务的部门。
     *
     * @param tenantId 租户 ID
     * @param requested 请求部门
     * @return 部门 ID
     */
    private Long resolveCreateDept(String tenantId, Long requested) {
        Long deptId = requested;
        String current = SecurityUtils.getCurrentTenantId();
        if (deptId == null && current != null && !current.isBlank() && !"0".equals(current)) {
            deptId = SecurityUtils.getCurrentDeptId();
        }
        if (deptId != null) {
            deptService.requireDept(tenantId, deptId);
        }
        return deptId;
    }

    private boolean hasTenantContext() {
        String current = SecurityUtils.getCurrentTenantId();
        return current != null && !current.isBlank();
    }

    /**
     * 将 ServiceRegisterRequest 转换为 OpsServiceEntity
     * @param req 服务注册请求
     * @param tenantId 租户 ID
     * @param deptId 部门 ID
     * @return OpsServiceEntity
     */
    private OpsServiceEntity convertToEntity(ServiceRegisterRequest req, String tenantId, Long deptId) {
        OpsServiceEntity entity = new OpsServiceEntity();
        entity.setName(req.getName());
        entity.setTenantId(tenantId);
        entity.setDeptId(deptId);
        entity.setBaseUrl(req.getBaseUrl());
        entity.setHealthPath(req.getHealthPath() != null ? req.getHealthPath() : "/actuator/health");
        entity.setMetricsPath(req.getMetricsPath() != null ? req.getMetricsPath() : "/actuator/metrics");
        entity.setOwner(req.getOwner());
        entity.setEnv(req.getEnv() != null ? req.getEnv() : "dev");
        entity.setTags(toJson(req.getTags()));
        entity.setStatus("UNKNOWN");
        return entity;
    }

    /**
     * 将 OpsServiceEntity 转换为 ServiceResponse
     * @param entity OpsServiceEntity
     * @return ServiceResponse
     */
    private ServiceResponse toResponse(OpsServiceEntity entity) {
        ServiceResponse response = ServiceResponse.builder().build();
        BeanUtils.copyProperties(entity, response);
        return response;
    }

    /**
     * 将 Map 转换为 JSON 字符串
     * @param map Map
     * @return JSON 字符串
     */
    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("tags 序列化失败", e);
            return null;
        }
    }
}
