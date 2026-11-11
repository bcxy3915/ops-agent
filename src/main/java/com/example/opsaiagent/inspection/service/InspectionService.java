package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.dto.InspectionReport;
import com.example.opsaiagent.inspection.dto.Observation;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.ops.mapper.OpsServiceMapper;
import com.example.opsaiagent.rbac.service.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 首个巡检切片：授权后实时采集，使用规则判断，不调用模型或改变服务状态。 */
@Service
@RequiredArgsConstructor
public class InspectionService {
    private final OpsServiceMapper serviceMapper;
    private final DataScope scope;
    private final InspectionCollector collector;

    @Value("${ops-agent.inspection.cpu-threshold:0.9}")
    private double cpuThreshold;

    @Value("${ops-agent.inspection.pool-threshold:0.9}")
    private double poolThreshold;

    /**
     * 按不可歧义的服务 ID 执行一次只读巡检，先鉴权再发出请求。
     * @param serviceId 已登记服务 ID
     * @return 规则报告与实时证据；缺失指标不会被补成零
     */
    public InspectionReport inspect(String serviceId) {
        String tenant = scope.tenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new BusinessException(ErrorCode.TENANT_REQUIRED);
        }
        OpsServiceEntity service = serviceMapper.selectById(serviceId);
        if (service == null || (!scope.isPlatformTenant()
                && (!tenant.equals(service.getTenantId()) || (scope.deptId() != null
                && !scope.visibleDeptIds().contains(service.getDeptId()))))) {
            throw new BusinessException(ErrorCode.SERVICE_NOT_FOUND);
        }
        long startedAt = System.currentTimeMillis();
        List<Observation> evidence = new ArrayList<>();
        evidence.add(collector.health(service));
        for (String metric : List.of("process.cpu.usage", "hikaricp.connections.active",
                "hikaricp.connections.max", "hikaricp.connections.pending")) {
            evidence.add(collector.metric(service, metric));
        }
        List<String> findings = new ArrayList<>();
        Object health = evidence.getFirst().value();
        if ("DOWN".equals(health) || "OUT_OF_SERVICE".equals(health)) {
            findings.add("健康端点明确报告 " + health);
        }
        Double cpu = value(evidence, "process.cpu.usage");
        Double active = value(evidence, "hikaricp.connections.active");
        Double max = value(evidence, "hikaricp.connections.max");
        Double pending = value(evidence, "hikaricp.connections.pending");
        if (cpu != null && cpu >= cpuThreshold) {
            findings.add("CPU 使用率达到巡检阈值");
        }
        if (active != null && max != null && max > 0 && active / max >= poolThreshold) {
            findings.add("连接池使用率达到巡检阈值");
        }
        if (pending != null && pending > 0) {
            findings.add("连接池存在等待请求");
        }
        boolean incomplete = evidence.stream().anyMatch(item -> !"AVAILABLE".equals(item.status()));
        if (max != null && max <= 0) {
            incomplete = true;
        }
        String status = !findings.isEmpty() ? "ABNORMAL"
                : incomplete ? "INSUFFICIENT_EVIDENCE" : "HEALTHY";
        return new InspectionReport(serviceId, service.getName(), startedAt,
                System.currentTimeMillis(), status, List.copyOf(findings), List.copyOf(evidence));
    }

    /**
     * 提取已成功采集的指标值。
     * @param evidence 本轮证据
     * @param name 指标名
     * @return 数值，缺失时为 null
     */
    private Double value(List<Observation> evidence, String name) {
        return evidence.stream().filter(item -> item.name().equals(name)
                        && "AVAILABLE".equals(item.status()) && item.value() instanceof Double)
                .map(item -> (Double) item.value()).findFirst().orElse(null);
    }
}
