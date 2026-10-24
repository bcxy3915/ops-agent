package com.example.opsaiagent.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.rbac.entity.TenantEntity;
import com.example.opsaiagent.rbac.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 租户服务。
 *
 * 提供租户查询与状态校验；登录时用于拒绝已停用租户。
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    /** 租户状态：正常 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    private final TenantMapper tenantMapper;

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
}
