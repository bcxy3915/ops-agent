package com.example.opsaiagent.notification.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.notification.dto.NotificationChannelRequest;
import com.example.opsaiagent.notification.dto.NotificationChannelResponse;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.service.NotificationChannelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知渠道 Controller 测试（T11）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通知渠道接口")
class NotificationChannelControllerTest {

    @Mock
    private NotificationChannelService notificationChannelService;

    @InjectMocks
    private NotificationChannelController controller;

    private NotificationChannelConfigEntity entity(Long id) {
        NotificationChannelConfigEntity e = new NotificationChannelConfigEntity();
        e.setId(id);
        e.setName("飞书");
        e.setType("FEISHU_WEBHOOK");
        e.setEnabled(true);
        e.setConfig("{\"webhookUrl\":\"https://hook\"}");
        return e;
    }

    @Test
    @DisplayName("列表：返回渠道列表")
    void list() {
        when(notificationChannelService.list(null, null)).thenReturn(List.of(entity(1L)));
        ApiResponse<List<NotificationChannelResponse>> resp = controller.list(null, null);
        assertThat(resp.getData()).hasSize(1);
        assertThat(resp.getData().get(0).getName()).isEqualTo("飞书");
    }

    @Test
    @DisplayName("创建：返回创建结果")
    void create() {
        when(notificationChannelService.create(any(NotificationChannelRequest.class)))
                .thenReturn(entity(1L));
        ApiResponse<NotificationChannelResponse> resp = controller.create(new NotificationChannelRequest());
        assertThat(resp.getData().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("更新：返回更新结果")
    void update() {
        when(notificationChannelService.update(anyLong(), any(NotificationChannelRequest.class)))
                .thenReturn(entity(1L));
        ApiResponse<NotificationChannelResponse> resp = controller.update(1L, new NotificationChannelRequest());
        assertThat(resp.getData().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("启停：默认启用（body 为空）")
    void setEnabled_defaultTrue() {
        when(notificationChannelService.setEnabled(1L, true)).thenReturn(entity(1L));
        ApiResponse<NotificationChannelResponse> resp = controller.setEnabled(1L, null);
        assertThat(resp.getData().getEnabled()).isTrue();
    }

    @Test
    @DisplayName("启停：按 body 显式禁用")
    void setEnabled_false() {
        when(notificationChannelService.setEnabled(1L, false)).thenReturn(entity(1L));
        ApiResponse<NotificationChannelResponse> resp = controller.setEnabled(1L, Map.of("enabled", false));
        assertThat(resp.getData().getEnabled()).isTrue(); // 返回的是 entity，enabled 未改（Service 决定）
    }

    @Test
    @DisplayName("删除：返回删除 ID")
    void delete() {
        ApiResponse<Map<String, Object>> resp = controller.delete(1L);
        assertThat(resp.getData().get("deleted")).isEqualTo(1L);
        verify(notificationChannelService).delete(1L);
    }

    @Test
    @DisplayName("测试发送：返回发送记录")
    void test() {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setStatus("SUCCESS");
        when(notificationChannelService.testSend(1L)).thenReturn(record);
        ApiResponse<com.example.opsaiagent.notification.dto.NotificationRecordResponse> resp = controller.test(1L);
        assertThat(resp.getData().getStatus()).isEqualTo("SUCCESS");
    }
}
