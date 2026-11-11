package com.example.opsaiagent.automation.service;

import com.example.opsaiagent.automation.dto.AgentTaskDetail;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.service.InspectionService;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 首版同步巡检编排；网络采集在数据库事务外执行，不伪装为可恢复后台工作流。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InspectionTaskService {
    private final InspectionService inspection;
    private final InspectionTaskPersistence persistence;
    private final AgentTaskQueryService queries;

    /**
     * 创建任务、执行只读巡检并原子保存结果。
     * @param serviceId 已登记服务 ID
     * @return 已持久化的任务详情
     */
    public AgentTaskDetail run(String serviceId) {
        var target = inspection.requireVisibleService(serviceId);
        String username = SecurityUtils.getCurrentUsername();
        String requesterTenant = SecurityUtils.getCurrentTenantId();
        if (username == null || requesterTenant == null || requesterTenant.isBlank()
                || target.getTenantId() == null || target.getTenantId().isBlank()) {
            throw new BusinessException(ErrorCode.TENANT_REQUIRED, "缺少可信发起身份或目标租户");
        }
        var task = persistence.begin(target, requesterTenant, username);
        try {
            var report = inspection.inspect(serviceId);
            var current = inspection.requireVisibleService(serviceId);
            if (!Objects.equals(target.getTenantId(), current.getTenantId())
                    || !Objects.equals(target.getDeptId(), current.getDeptId())) {
                throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID);
            }
            persistence.complete(task, report);
        } catch (Exception failure) {
            try {
                persistence.fail(task);
            } catch (Exception persistenceFailure) {
                log.warn("巡检任务失败状态未保存，任务 ID：{}，异常类型：{}", task.getId(),
                        persistenceFailure.getClass().getSimpleName());
            }
            log.warn("巡检任务执行失败，任务 ID：{}，异常类型：{}", task.getId(), failure.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "巡检任务执行失败，任务 ID：" + task.getId());
        }
        return queries.detail(task.getId());
    }
}
