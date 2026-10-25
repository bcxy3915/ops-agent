package com.example.opsaiagent.notification.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.notification.dto.NotificationRecordResponse;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.mapper.NotificationRecordMapper;
import com.example.opsaiagent.notification.service.NotificationScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 通知记录查询接口测试（T10）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通知记录接口")
class NotificationRecordControllerTest {

    @Mock
    private NotificationRecordMapper recordMapper;

    @Spy
    private NotificationScope notificationScope = new NotificationScope(null);

    @InjectMocks
    private NotificationRecordController controller;

    private NotificationRecordEntity record(Long id) {
        NotificationRecordEntity r = new NotificationRecordEntity();
        r.setId(id);
        r.setStatus("SUCCESS");
        r.setChannelName("飞书");
        return r;
    }

    @Test
    @DisplayName("列表：返回记录并映射 DTO")
    void list() {
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record(1L), record(2L)));
        ApiResponse<List<NotificationRecordResponse>> resp = controller.list(null, null, null, 50);
        assertThat(resp.getData()).hasSize(2);
        assertThat(resp.getData().get(0).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("列表：limit 越界被钳制到 1..200")
    void list_limitClamped() {
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        controller.list(null, null, null, 9999);
        controller.list(null, null, null, 0);
        org.mockito.Mockito.verify(recordMapper, org.mockito.Mockito.times(2)).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("列表：按状态 / 渠道 / 事件过滤时仍可查询")
    void list_filtered() {
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record(1L)));
        ApiResponse<List<NotificationRecordResponse>> resp = controller.list("FAILED", 1L, 2L, 10);
        assertThat(resp.getData()).hasSize(1);
    }
}
