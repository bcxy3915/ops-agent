package com.example.opsaiagent.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.dto.TenantCreateRequest;
import com.example.opsaiagent.rbac.dto.TenantUpdateRequest;
import com.example.opsaiagent.rbac.entity.TenantEntity;
import com.example.opsaiagent.rbac.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 租户服务。
 *
 * 提供租户查询、状态校验与平台侧 CRUD；登录时用于拒绝已停用租户。
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    /** 租户状态：正常 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 租户状态：停用 */
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    private final TenantMapper tenantMapper;

    /**
     * 查询全部租户（平台管理员）。
     *
     * @return 按编码排序的租户列表
     */
    public List<TenantEntity> listAll() {
        return tenantMapper.selectList(new LambdaQueryWrapper<TenantEntity>()
                .orderByAsc(TenantEntity::getCode));
    }

    /**
     * 创建租户。
     *
     * @param request 创建请求
     * @return 创建后的租户
     */
    @Transactional
    public TenantEntity create(TenantCreateRequest request) {
        String code = request.getCode() == null ? "" : request.getCode().trim();
        if (findByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.TENANT_CODE_EXISTS, "租户编码已存在: " + code);
        }

        LocalDateTime now = LocalDateTime.now();
        TenantEntity entity = new TenantEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setCode(code);
        entity.setName(request.getName() == null ? code : request.getName().trim());
        entity.setStatus(STATUS_ACTIVE);
        entity.setKnowledgeReviewMode(isBlank(request.getKnowledgeReviewMode())
                ? "DEPT_ONLY" : request.getKnowledgeReviewMode());
        entity.setReviewFourEyes(request.getReviewFourEyes() == null
                ? Boolean.TRUE : request.getReviewFourEyes());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        tenantMapper.insert(entity);
        return entity;
    }

    /**
     * 更新租户基础信息。
     *
     * @param id      租户 ID
     * @param request 更新请求
     * @return 更新后的租户
     */
    @Transactional
    public TenantEntity update(String id, TenantUpdateRequest request) {
        TenantEntity entity = requireTenant(id);
        if (request.getName() != null) {
            entity.setName(request.getName().trim());
        }
        if (request.getKnowledgeReviewMode() != null) {
            entity.setKnowledgeReviewMode(request.getKnowledgeReviewMode());
        }
        if (request.getReviewFourEyes() != null) {
            entity.setReviewFourEyes(request.getReviewFourEyes());
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(entity);
        return entity;
    }

    /**
     * 启用/停用租户。
     *
     * @param id     租户 ID
     * @param status ACTIVE / SUSPENDED
     * @return 更新后的租户
     */
    @Transactional
    public TenantEntity updateStatus(String id, String status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_SUSPENDED.equals(status)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "租户状态不合法: " + status);
        }
        TenantEntity entity = requireTenant(id);
        entity.setStatus(status);
        entity.setUpdatedAt(LocalDateTime.now());
        tenantMapper.updateById(entity);
        return entity;
    }

    /**
     * 按 ID 查询租户。
     *
     * @param tenantId 租户 ID
     * @return 租户；不存在时返回空
     */
    public Optional<TenantEntity> findById(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantMapper.selectById(tenantId));
    }

    /**
     * 按编码查询租户。
     *
     * @param code 租户编码
     * @return 租户；不存在时返回空
     */
    public Optional<TenantEntity> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tenantMapper.selectOne(
                new LambdaQueryWrapper<TenantEntity>().eq(TenantEntity::getCode, code)));
    }

    /**
     * 判断租户是否处于可用状态。
     *
     * @param tenantId 租户 ID
     * @return true=ACTIVE；不存在或非 ACTIVE 返回 false
     */
    public boolean isActive(String tenantId) {
        return findById(tenantId)
                .map(t -> STATUS_ACTIVE.equalsIgnoreCase(t.getStatus()))
                .orElse(false);
    }

    /**
     * 查询租户，不存在时抛业务异常。
     *
     * @param id 租户 ID
     * @return 租户
     */
    public TenantEntity requireTenant(String id) {
        return findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND, "租户不存在: " + id));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
