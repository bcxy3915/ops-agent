package com.example.opsaiagent.automation.diagnosis;

import com.example.opsaiagent.automation.dto.AgentTaskDetail;
import com.example.opsaiagent.automation.service.AgentTaskQueryService;
import com.example.opsaiagent.automation.service.InspectionTaskPersistence;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.service.InspectionService;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 独立于聊天的同步诊断入口，数据库事务不跨越模型和工具调用。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutonomousDiagnosisService {
    private final InspectionService inspection;
    private final InspectionTaskPersistence persistence;
    private final DiagnosisLoop loop;
    private final AgentTaskQueryService queries;

    /**
     * 创建诊断任务、实时巡检并动态取证，最后持久化完整结果。
     * @param serviceId 已登记目标服务 ID
     * @return 持久化的诊断任务详情
     */
    public AgentTaskDetail diagnose(String serviceId) {
        var target = inspection.requireVisibleService(serviceId);
        String username = SecurityUtils.getCurrentUsername();
        String tenant = SecurityUtils.getCurrentTenantId();
        if (username == null || tenant == null || target.getTenantId() == null) {
            throw new BusinessException(ErrorCode.TENANT_REQUIRED);
        }
        var task = persistence.beginDiagnosis(target, tenant, username);
        try {
            var baseline = inspection.inspect(serviceId);
            var result = loop.run(baseline);
            var current = inspection.requireVisibleService(serviceId);
            if (!Objects.equals(target.getTenantId(), current.getTenantId())
                    || !Objects.equals(target.getDeptId(), current.getDeptId())) {
                throw new BusinessException(ErrorCode.AGENT_TASK_STATE_INVALID);
            }
            persistence.completeDiagnosis(task, baseline, result);
        } catch (Exception failure) {
            try {
                persistence.fail(task);
            } catch (Exception saveFailure) {
                log.warn("诊断失败状态未保存，任务 ID：{}，异常类型：{}", task.getId(), saveFailure.getClass().getSimpleName());
            }
            log.warn("自主诊断任务失败，任务 ID：{}，异常类型：{}", task.getId(), failure.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "诊断任务失败，任务 ID：" + task.getId());
        }
        return queries.detail(task.getId());
    }
}
