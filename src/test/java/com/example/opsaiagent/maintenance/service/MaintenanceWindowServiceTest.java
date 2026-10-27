package com.example.opsaiagent.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.alert.service.AlertEventService;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.maintenance.dto.MaintenanceWindowCreateRequest;
import com.example.opsaiagent.maintenance.dto.MaintenanceWindowResponse;
import com.example.opsaiagent.maintenance.entity.MaintenanceWindowEntity;
import com.example.opsaiagent.maintenance.mapper.MaintenanceWindowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MaintenanceWindowService 单元测试（P1 规格 5）
 * 覆盖：创建（校验/多服务落库/存量静默）、列表 activeOnly、提前结束、窗口判断、到期扫描
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("维护窗口服务")
class MaintenanceWindowServiceTest {

    @Mock
    private MaintenanceWindowMapper windowMapper;
    @Mock
    private AlertEventMapper alertEventMapper;
    @Mock
    private AlertEventService alertEventService;

    @InjectMocks
    private MaintenanceWindowService service;

    private MaintenanceWindowEntity window(Long id, String service, LocalDateTime until) {
        MaintenanceWindowEntity w = new MaintenanceWindowEntity();
        w.setId(id);
        w.setService(service);
        w.setReason("发布窗口");
        w.setUntil(until);
        return w;
    }

    @Test
    @DisplayName("创建：到期时间必须晚于当前时间")
    void create_rejectsPastUntil() {
        MaintenanceWindowCreateRequest req = new MaintenanceWindowCreateRequest();
        req.setServices(List.of("svc-a"));
        req.setReason("发布");
        req.setUntil(LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BusinessException.class);
        verify(windowMapper, never()).insert(any(MaintenanceWindowEntity.class));
    }

    @Test
    @DisplayName("创建：多服务逐条落库 + 存量告警静默（复用单条静默）")
    void create_insertsWindowsAndSilencesExisting() {
        LocalDateTime until = LocalDateTime.now().plusHours(2);
        MaintenanceWindowCreateRequest req = new MaintenanceWindowCreateRequest();
        req.setServices(List.of("svc-a", "svc-b"));
        req.setReason("数据库升级");
        req.setUntil(until);

        AlertEventEntity firing = new AlertEventEntity();
        firing.setId(1L);
        firing.setService("svc-a");
        firing.setStatus("FIRING");
        AlertEventEntity acked = new AlertEventEntity();
        acked.setId(2L);
        acked.setService("svc-b");
        acked.setStatus("ACKNOWLEDGED");
        when(alertEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(firing, acked));

        List<MaintenanceWindowResponse> created = service.create(req);

        assertThat(created).hasSize(2);
        verify(windowMapper, times(2)).insert(any(MaintenanceWindowEntity.class));
        verify(alertEventService).silence(1L, MaintenanceWindowService.SILENCE_REASON_PREFIX + "数据库升级", until, null);
        verify(alertEventService).silence(2L, MaintenanceWindowService.SILENCE_REASON_PREFIX + "数据库升级", until, null);
    }

    @Test
    @DisplayName("创建：已 SILENCED 存量告警仅顺延到期时间并标记维护来源")
    void create_pushesAlreadySilencedUntil() {
        LocalDateTime until = LocalDateTime.now().plusHours(2);
        MaintenanceWindowCreateRequest req = new MaintenanceWindowCreateRequest();
        req.setServices(List.of("svc-a"));
        req.setReason("发布");
        req.setUntil(until);

        AlertEventEntity silenced = new AlertEventEntity();
        silenced.setId(9L);
        silenced.setService("svc-a");
        silenced.setStatus("SILENCED");
        when(alertEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(silenced));

        service.create(req);

        verify(alertEventService, never()).silence(any(), any(), any(), any());
        ArgumentCaptor<AlertEventEntity> captor = ArgumentCaptor.forClass(AlertEventEntity.class);
        verify(alertEventMapper).updateById(captor.capture());
        assertThat(captor.getValue().getSilencedUntil()).isEqualTo(until);
        assertThat(captor.getValue().getSilencedReason()).startsWith(MaintenanceWindowService.SILENCE_REASON_PREFIX);
    }

    @Test
    @DisplayName("列表：activeOnly 过滤已结束/到期窗口")
    void list_activeOnlyFilters() {
        LocalDateTime now = LocalDateTime.now();
        MaintenanceWindowEntity active = window(1L, "svc-a", now.plusHours(1));
        MaintenanceWindowEntity ended = window(2L, "svc-b", now.plusHours(1));
        ended.setEndedAt(now);
        MaintenanceWindowEntity expired = window(3L, "svc-c", now.minusMinutes(1));
        when(windowMapper.selectList(any(Wrapper.class))).thenReturn(List.of(active, ended, expired));

        List<MaintenanceWindowResponse> all = service.list(false);
        assertThat(all).hasSize(3);

        List<MaintenanceWindowResponse> onlyActive = service.list(true);
        assertThat(onlyActive).hasSize(1);
        assertThat(onlyActive.get(0).getService()).isEqualTo("svc-a");
    }

    @Test
    @DisplayName("提前结束：不存在抛异常，正常置 ended_at")
    void end_marksEndedAt() {
        MaintenanceWindowEntity w = window(5L, "svc-a", LocalDateTime.now().plusHours(1));
        when(windowMapper.selectById(5L)).thenReturn(w);

        MaintenanceWindowResponse resp = service.end(5L);

        assertThat(resp.getEndedAt()).isNotNull();
        assertThat(resp.isActive()).isFalse();
        verify(windowMapper).updateById(w);
    }

    @Test
    @DisplayName("提前结束：窗口不存在抛 MAINTENANCE_WINDOW_NOT_FOUND")
    void end_notFoundThrows() {
        when(windowMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.end(99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("提前结束：已结束的窗口重复结束抛异常")
    void end_alreadyEndedThrows() {
        MaintenanceWindowEntity w = window(6L, "svc-a", LocalDateTime.now().plusHours(1));
        w.setEndedAt(LocalDateTime.now());
        when(windowMapper.selectById(6L)).thenReturn(w);
        assertThatThrownBy(() -> service.end(6L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("窗口判断：存在未结束且未到期的窗口返回 true")
    void isServiceInWindow_trueWhenActive() {
        when(windowMapper.selectList(any(Wrapper.class))).thenReturn(
                List.of(window(1L, "svc-a", LocalDateTime.now().plusHours(1))));
        assertThat(service.isServiceInWindow("svc-a", LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("窗口判断：无有效窗口返回 false")
    void isServiceInWindow_falseWhenNone() {
        when(windowMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(service.isServiceInWindow("svc-a", LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("到期扫描：到期窗口置 ended_at 并返回涉及服务（去重）")
    void expireDueWindows_endsAndReturnsServices() {
        LocalDateTime now = LocalDateTime.now();
        MaintenanceWindowEntity w1 = window(1L, "svc-a", now.minusMinutes(1));
        MaintenanceWindowEntity w2 = window(2L, "svc-a", now.minusMinutes(2));
        when(windowMapper.selectList(any(Wrapper.class))).thenReturn(List.of(w1, w2));

        List<String> services = service.expireDueWindows(now);

        assertThat(services).containsExactly("svc-a");
        assertThat(w1.getEndedAt()).isEqualTo(now);
        assertThat(w2.getEndedAt()).isEqualTo(now);
        verify(windowMapper, times(2)).updateById(any(MaintenanceWindowEntity.class));
    }
}
