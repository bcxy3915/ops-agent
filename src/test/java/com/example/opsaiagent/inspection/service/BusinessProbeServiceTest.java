package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.config.BusinessProbeProperties;
import com.example.opsaiagent.inspection.config.BusinessProbeProperties.Definition;
import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证服务授权与预登记探针绑定，拒绝任意目标访问。 */
class BusinessProbeServiceTest {
    private final InspectionService inspection = mock(InspectionService.class);
    private final BusinessProbeProperties properties = new BusinessProbeProperties();
    private final BusinessProbeCollector collector = mock(BusinessProbeCollector.class);
    private final BusinessProbeService service = new BusinessProbeService(inspection, properties, collector);

    /**
     * 授权失败时不触发任何探针请求。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void deniesUnauthorizedTargetBeforeCollection() {
        when(inspection.requireVisibleService("other-tenant"))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_NOT_FOUND));
        assertThatThrownBy(() -> service.check("other-tenant", "products")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(collector);
    }

    /**
     * 探针未配置或绑定其他服务时不发起网络请求。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsMissingOrMismatchedProbe() {
        when(inspection.requireVisibleService("id")).thenReturn(new OpsServiceEntity());
        assertThatThrownBy(() -> service.check("id", "unknown")).isInstanceOf(BusinessException.class);
        Definition definition = new Definition();
        definition.setServiceId("other-id");
        properties.getProbes().put("products", definition);
        assertThatThrownBy(() -> service.check("id", "products")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(collector);
    }

    /**
     * 正常路径只将已授权服务与原登记定义交给采集器。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void executesOnlyBoundDefinition() {
        OpsServiceEntity target = new OpsServiceEntity();
        when(inspection.requireVisibleService("id")).thenReturn(target);
        Definition definition = new Definition();
        definition.setServiceId("id");
        definition.setPath("/products");
        properties.getProbes().put("products", definition);
        service.check("id", "products");
        verify(collector).collect(target, "products", definition);
    }
}
