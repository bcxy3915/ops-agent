package com.example.opsaiagent.audit.service;

import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 异步保存审计日志，避免阻塞业务
     * @param entity 审计日志实体
     */
    @Async("auditExecutor")
    public void save(AuditLogEntity entity) {
        try {
            if (entity == null) {
                log.warn("审计日志实体为空，跳过保存");
                return;
            }
            if (!StringUtils.hasText(entity.getTenantId())) {
                String tenantId = TenantContext.get();
                entity.setTenantId(StringUtils.hasText(tenantId) ? tenantId : "t-default");
            }
            if (entity.getDeptId() == null) {
                entity.setDeptId(DeptContext.get());
            }
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("审计日志保存失败", e);
        }
    }
}