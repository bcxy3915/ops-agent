package com.example.opsaiagent.notification.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotificationProperties;
import com.example.opsaiagent.notification.Notifier;
import com.example.opsaiagent.notification.NotifierRegistry;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.mapper.NotificationChannelMapper;
import com.example.opsaiagent.notification.mapper.NotificationRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知中心测试（T10）：统一入口、发送记录、失败重试
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通知中心")
class NotificationCenterTest {

    @Mock
    private NotificationChannelMapper channelMapper;
    @Mock
    private NotificationRecordMapper recordMapper;
    @Mock
    private NotifierRegistry notifierRegistry;
    @Mock
    private NotificationTemplateService templateService;
    @Mock
    private Notifier notifier;

    @Spy
    private NotificationProperties properties = new NotificationProperties();

    @InjectMocks
    private NotificationCenter notificationCenter;

    @BeforeEach
    void setUp() {
        properties.setMaxRetry(2);
        properties.setRetryDelaySec(300);
    }

    private NotificationChannelConfigEntity channel(Long id, String name, String type) {
        NotificationChannelConfigEntity channel = new NotificationChannelConfigEntity();
        channel.setId(id);
        channel.setName(name);
        channel.setType(type);
        channel.setEnabled(true);
        channel.setConfig("{\"webhookUrl\":\"https://hook\"}");
        return channel;
    }

    private AlertEventEntity event(Long id) {
        AlertEventEntity event = new AlertEventEntity();
        event.setId(id);
        event.setRuleId(1L);
        event.setRuleName("cpu-high");
        event.setService("svc-a");
        event.setMetric("cpu");
        event.setStatus("FIRING");
        event.setFiredAt(LocalDateTime.now());
        return event;
    }

    // ==================== sendOnEvent ====================

    @Test
    @DisplayName("无启用渠道：跳过通知，不落库")
    void sendOnEvent_noChannels() {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        notificationCenter.sendOnEvent(event(1L));
        verify(recordMapper, never()).insert(any(NotificationRecordEntity.class));
    }

    @Test
    @DisplayName("发送成功：记录 PENDING→SUCCESS 落库")
    void sendOnEvent_success() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK")));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("告警通知 - cpu-high").content("cpu=95").build());

        notificationCenter.sendOnEvent(event(1L));

        verify(recordMapper).insert(any(NotificationRecordEntity.class));
        verify(notifier).send(any(NotificationChannelConfigEntity.class), any());
        // 记录最终 SUCCESS
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationRecordEntity.class);
        verify(recordMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("发送失败：记录 FAILED，设置重试时间，不抛异常")
    void sendOnEvent_failure() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK")));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("t").content("c").build());
        doThrow(new RuntimeException("timeout")).when(notifier).send(any(), any());

        notificationCenter.sendOnEvent(event(1L));

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        NotificationRecordEntity record = captor.getValue();
        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getErrorMessage()).contains("timeout");
        assertThat(record.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("多渠道：逐个发送")
    void sendOnEvent_multipleChannels() throws Exception {
        when(channelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(channel(1L, "飞书", "FEISHU_WEBHOOK"), channel(2L, "邮件", "EMAIL")));
        when(notifierRegistry.get(any(NotificationChannelType.class))).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("t").content("c").build());

        notificationCenter.sendOnEvent(event(1L));

        verify(notifier, org.mockito.Mockito.times(2)).send(any(), any());
        verify(recordMapper, org.mockito.Mockito.times(2)).insert(any(NotificationRecordEntity.class));
    }

    // ==================== sendTest ====================

    @Test
    @DisplayName("测试发送：渠道不存在抛异常")
    void sendTest_channelNotFound() {
        when(channelMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> notificationCenter.sendTest(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("测试发送：渠道存在则发送并返回记录")
    void sendTest_success() throws Exception {
        NotificationChannelConfigEntity ch = channel(1L, "飞书", "FEISHU_WEBHOOK");
        when(channelMapper.selectById(1L)).thenReturn(ch);
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        when(templateService.render(any(AlertEventEntity.class), any())).thenReturn(
                com.example.opsaiagent.notification.NotificationContent.builder()
                        .title("测试通知 - 测试通知").content("测试").build());

        NotificationRecordEntity record = notificationCenter.sendTest(1L);

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getAlertEventId()).isNull();
        verify(notifier).send(any(), any());
    }

    // ==================== retryPending ====================

    @Test
    @DisplayName("重试：无到期记录不处理")
    void retry_none() {
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        notificationCenter.retryPending(LocalDateTime.now());
        verify(channelMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("重试：发送成功记录转 SUCCESS")
    void retry_success() throws Exception {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(1L);
        record.setChannelType("FEISHU_WEBHOOK");
        record.setStatus("FAILED");
        record.setRetryCount(0);
        record.setTitle("t");
        record.setContent("c");
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        NotificationChannelConfigEntity ch = channel(1L, "飞书", "FEISHU_WEBHOOK");
        when(channelMapper.selectById(1L)).thenReturn(ch);
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("SUCCESS");
        assertThat(record.getSentAt()).isNotNull();
        verify(recordMapper).updateById(record);
    }

    @Test
    @DisplayName("重试：再次失败则 retryCount 递增并顺延重试时间")
    void retry_failureIncrements() throws Exception {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(1L);
        record.setChannelType("FEISHU_WEBHOOK");
        record.setStatus("FAILED");
        record.setRetryCount(1);
        record.setTitle("t");
        record.setContent("c");
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, "飞书", "FEISHU_WEBHOOK"));
        when(notifierRegistry.get(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(notifier);
        doThrow(new RuntimeException("still down")).when(notifier).send(any(), any());

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getRetryCount()).isEqualTo(2);
        assertThat(record.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("重试：渠道已删除则标记失败不再重试")
    void retry_channelDeleted() throws Exception {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(99L);
        record.setChannelType("FEISHU_WEBHOOK");
        record.setStatus("FAILED");
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        when(channelMapper.selectById(99L)).thenReturn(null);

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("FAILED");
        verify(notifier, never()).send(any(), any());
        verify(recordMapper).updateById(record);
    }

    @Test
    @DisplayName("渠道类型无适配器：重试时失败并保留")
    void retry_unsupportedType() {
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setId(1L);
        record.setChannelId(1L);
        record.setChannelType("UNKNOWN_TYPE");
        record.setStatus("FAILED");
        record.setRetryCount(0);
        when(recordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        when(channelMapper.selectById(1L)).thenReturn(channel(1L, "未知", "UNKNOWN_TYPE"));

        notificationCenter.retryPending(LocalDateTime.now());

        assertThat(record.getStatus()).isEqualTo("FAILED");
        assertThat(record.getRetryCount()).isEqualTo(1);
    }
}
