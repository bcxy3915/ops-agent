package com.example.opsaiagent.controller;

import com.example.opsaiagent.dto.ApiResponse;
import com.example.opsaiagent.dto.ServiceRegisterRequest;
import com.example.opsaiagent.dto.ServiceResponse;
import com.example.opsaiagent.dto.ServiceUpdateRequest;
import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.service.OpsServiceManager;
import com.example.opsaiagent.service.ServiceHealthChecker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceController {

    private final OpsServiceManager serviceManager;
    private final ServiceHealthChecker healthChecker;
    private final ObjectMapper objectMapper;

    /**
     * 注册服务
     * @param request 服务注册请求
     * @return 注册结果
     */
    @PostMapping
    public ApiResponse<ServiceResponse> register(@Valid @RequestBody ServiceRegisterRequest request) {
        if (serviceManager.findByName(request.getName()).isPresent()) {
            throw new BusinessException(ErrorCode.SERVICE_ALREADY_EXISTS,
                    "服务已存在: " + request.getName());
        }
        OpsServiceEntity entity = convertToEntity(request);
        serviceManager.save(entity);
        return ApiResponse.success(toResponse(entity));
    }

    /**
     * 列出所有服务
     * @param env 环境
     * @param status 状态
     * @return 服务列表
     */
    @GetMapping
    public ApiResponse<List<ServiceResponse>> list(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String status) {
        List<OpsServiceEntity> list;
        if (env != null) {
            list = serviceManager.findByEnv(env);
        } else if (status != null) {
            list = serviceManager.findByStatus(status);
        } else {
            list = serviceManager.listAll();
        }
        return ApiResponse.success(list.stream().map(this::toResponse).toList());
    }

    /**
     * 获取服务详情
     * @param name 服务名
     * @return 服务详情
     */
    @GetMapping("/{name}")
    public ApiResponse<ServiceResponse> get(@PathVariable String name) {
        OpsServiceEntity entity = serviceManager.findByName(name)
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_NOT_FOUND,
                        "服务不存在: " + name));
        return ApiResponse.success(toResponse(entity));
    }

    /**
     * 更新服务
     * @param name 服务名
     * @param request 更新请求
     * @return 更新结果
     */
    @PutMapping("/{name}")
    public ApiResponse<ServiceResponse> update(
            @PathVariable String name,
            @Valid @RequestBody ServiceUpdateRequest request) {
        OpsServiceEntity entity = serviceManager.findByName(name)
                .orElseThrow(() -> new BusinessException(ErrorCode.SERVICE_NOT_FOUND));
        if (request.getBaseUrl() != null) entity.setBaseUrl(request.getBaseUrl());
        if (request.getOwner() != null) entity.setOwner(request.getOwner());
        if (request.getEnv() != null) entity.setEnv(request.getEnv());
        if (request.getTags() != null) entity.setTags(toJson(request.getTags()));
        serviceManager.update(entity);
        return ApiResponse.success(toResponse(entity));
    }

    /**
     * 删除服务
     * @param name 服务名
     * @return 删除结果
     */
    @DeleteMapping("/{name}")
    public ApiResponse<Map<String, String>> delete(@PathVariable String name) {
        if (!serviceManager.deleteByName(name)) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND);
        }
        return ApiResponse.success(Map.of("deleted", name));
    }

    /**
     * 检查服务健康状态
     * @param name 服务名
     * @return 健康状态
     */
    @PostMapping("/{name}/check")
    public ApiResponse<Map<String, Object>> check(@PathVariable String name) {
        ServiceInfo info = new ServiceInfo() /* TODO 从 manager 查出并转换 */;
        String status = healthChecker.checkNow(name);
        return ApiResponse.success(Map.of(
                "name", name,
                "status", status,
                "checkedAt", LocalDateTime.now()
        ));
    }

    /**
     * 将 ServiceRegisterRequest 转换为 OpsServiceEntity
     * @param req 服务注册请求
     * @return OpsServiceEntity
     */
    private OpsServiceEntity convertToEntity(ServiceRegisterRequest req) {
        OpsServiceEntity entity = new OpsServiceEntity();
        entity.setName(req.getName());
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