package com.example.opsaiagent.automation.diagnosis;

import com.example.opsaiagent.inspection.config.BusinessProbeProperties;
import com.example.opsaiagent.inspection.config.InspectionLogProperties;
import com.example.opsaiagent.inspection.service.BusinessProbeService;
import com.example.opsaiagent.inspection.service.InspectionLogService;
import com.example.opsaiagent.inspection.service.InspectionMetricWindowService;
import com.example.opsaiagent.inspection.service.InspectionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证工具目录不会泄漏路径或开放其他目标服务。 */
class DiagnosisToolGatewayTest {
    private final InspectionService inspection = mock(InspectionService.class);
    private final InspectionMetricWindowService windows = mock(InspectionMetricWindowService.class);
    private final InspectionLogService logs = mock(InspectionLogService.class);
    private final BusinessProbeService probes = mock(BusinessProbeService.class);
    private final InspectionLogProperties logProperties = new InspectionLogProperties();
    private final BusinessProbeProperties probeProperties = new BusinessProbeProperties();
    private final DiagnosisToolGateway gateway = new DiagnosisToolGateway(inspection, windows, logs, probes,
            logProperties, probeProperties);

    /**
     * 仅列出当前服务绑定源的标识，不给模型文件路径。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void catalogContainsOnlyBoundIdentifiers() {
        var mine = new InspectionLogProperties.Source();
        mine.setServiceId("svc");
        mine.setRoot("D:/private-logs");
        var other = new InspectionLogProperties.Source();
        other.setServiceId("other-svc");
        logProperties.getSources().put("main", mine);
        logProperties.getSources().put("other", other);
        var result = gateway.catalog("svc");
        assertThat(result.get("LOGS")).containsExactly("main");
        assertThat(result.toString()).doesNotContain("private-logs", "other-svc");
    }

    /**
     * 未登记参数和写操作不得进入底层工具。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsUnboundLogsAndWriteOperations() {
        assertThatThrownBy(() -> gateway.execute("svc", "LOGS", "../../secret", 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.execute("svc", "DEPLOY", "latest", 2))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(windows, logs, probes);
    }

    /**
     * 时间窗与服务 ID 来自任务，模型只能选择指标名。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void fixesServiceAndWindowAtExecution() {
        gateway.execute("svc", "METRIC_WINDOW", "process.cpu.usage", 1000);
        verify(inspection).requireVisibleService("svc");
        verify(windows).compare("svc", "process.cpu.usage", 5, 1000);
    }
}
