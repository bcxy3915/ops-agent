package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.inspection.config.InspectionLogProperties;
import com.example.opsaiagent.inspection.config.InspectionLogProperties.Source;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证日志读取入口的授权、绑定及窗口预算。 */
class InspectionLogServiceTest {
    private final InspectionService inspection = mock(InspectionService.class);
    private final InspectionLogReader reader = mock(InspectionLogReader.class);
    private final InspectionLogProperties properties = new InspectionLogProperties();
    private final InspectionLogService service = new InspectionLogService(inspection, properties, reader);
    private final long end = 1_700_000_000_000L;

    /**
     * 权限失败必须发生在文件读取之前。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void deniesUnauthorizedServiceBeforeFileAccess() {
        doThrow(new BusinessException(ErrorCode.SERVICE_NOT_FOUND)).when(inspection).requireVisibleService("id");
        assertThatThrownBy(() -> service.read("id", "main", end - 60_000, end, 100)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reader);
    }

    /**
     * 未配置或绑定其他服务的日志源不允许读取。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsMissingOrMismatchedSources() {
        assertThatThrownBy(() -> service.read("id", "main", end - 60_000, end, 100)).isInstanceOf(BusinessException.class);
        Source source = new Source();
        source.setServiceId("other-id");
        properties.getSources().put("main", source);
        assertThatThrownBy(() -> service.read("id", "main", end - 60_000, end, 100)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(reader);
    }

    /**
     * 拒绝超长、倒置和未来时间窗及超额条数。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void rejectsInvalidBudgetsBeforeAccess() {
        assertThatThrownBy(() -> service.read("id", "main", end - 3_600_001, end, 100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.read("id", "main", end, end, 100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.read("id", "main", end - 60_000, end, 201)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.read("id", "main", end, Long.MAX_VALUE, 100)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(inspection, reader);
    }

    /**
     * 合法请求只传递服务端绑定定义，不接收请求路径。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void readsOnlyBoundSource() {
        Source source = new Source();
        source.setServiceId("id");
        properties.getSources().put("main", source);
        service.read("id", "main", end - 60_000, end, 100);
        verify(reader).read("id", "main", source, end - 60_000, end, 100);
    }
}
