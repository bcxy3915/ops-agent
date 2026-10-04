package com.example.opsaiagent.audit.service;

import com.example.opsaiagent.audit.entity.AuditLogEntity;
import com.example.opsaiagent.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("审计日志保存失败", e);
        }
    }
}