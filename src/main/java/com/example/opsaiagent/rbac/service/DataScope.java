package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据范围工具。
 *
 * 统一提供当前租户、当前部门与“本部门 + 下级部门”的可见部门集合。
 * 后续知识、服务、告警、诊断等模块在 Service 层统一调用，避免各写一套过滤条件。
 */
@Component
@RequiredArgsConstructor
public class DataScope {

    private final DeptService deptService;

    /**
     * 当前租户 ID。
     *
     * @return 租户 ID；未登录/系统任务时可能为 null
     */
    public String tenantId() {
        return TenantContext.get();
    }

    /**
     * 当前部门 ID。
     *
     * @return 部门 ID；未登录/系统任务时可能为 null
     */
    public Long deptId() {
        return DeptContext.get();
    }

    /**
     * 当前用户可见的部门 ID（本部门 + 全部下级）。
     *
     * @return 部门 ID 列表；无上下文时返回空列表
     */
    public List<Long> visibleDeptIds() {
        List<Long> deptIds = deptService.subtreeIds(tenantId(), deptId());
        return deptIds == null ? List.of() : deptIds;
    }

    /**
     * 是否处于平台保留租户。
     *
     * @return true=平台租户 0
     */
    public boolean isPlatformTenant() {
        return TenantContext.PLATFORM_TENANT_ID.equals(tenantId());
    }
}
