package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.config.BusinessProbeProperties;
import com.example.opsaiagent.inspection.dto.BusinessProbeReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 业务探针入口，授权后只执行绑定到当前目标的预登记定义。 */
@Service
@RequiredArgsConstructor
public class BusinessProbeService {
    private final InspectionService inspection;
    private final BusinessProbeProperties properties;
    private final BusinessProbeCollector collector;

    /**
     * 执行指定业务探针，不能由调用者改变 HTTP 方法、地址或断言。
     * @param serviceId 已登记服务 ID
     * @param probeId 预登记探针标识
     * @return 业务探针报告；无绑定时抛参数异常，不发出网络请求
     */
    public BusinessProbeReport check(String serviceId, String probeId) {
        var target = inspection.requireVisibleService(serviceId);
        var probe = properties.getProbes().get(probeId);
        if (probe == null || !serviceId.equals(probe.getServiceId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "业务探针未绑定到当前服务");
        }
        return collector.collect(target, probeId, probe);
    }
}
